package fun.ai.studio.controller.workspace.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiWorkspaceRunService;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/fun-ai/workspace/realtime")
@Tag(name = "Fun AI Workspace 实时通道", description = "在线编辑器实时：SSE（状态/日志），WS 终端请看 /doc/workspace-realtime.md")
public class FunAiWorkspaceRealtimeController {
    private final FunAiWorkspaceService funAiWorkspaceService;
    private final FunAiAppService funAiAppService;
    private final WorkspaceActivityTracker activityTracker;
    private final WorkspaceProperties workspaceProperties;
    private final FunAiWorkspaceRunService funAiWorkspaceRunService;
    private final ObjectMapper objectMapper;

    public FunAiWorkspaceRealtimeController(
            FunAiWorkspaceService funAiWorkspaceService,
            FunAiAppService funAiAppService,
            WorkspaceActivityTracker activityTracker,
            WorkspaceProperties workspaceProperties,
            FunAiWorkspaceRunService funAiWorkspaceRunService,
            ObjectMapper objectMapper
    ) {
        this.funAiWorkspaceService = funAiWorkspaceService;
        this.funAiAppService = funAiAppService;
        this.activityTracker = activityTracker;
        this.workspaceProperties = workspaceProperties;
        this.funAiWorkspaceRunService = funAiWorkspaceRunService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "SSE：推送运行状态与 dev.log 增量",
            description = "用于在线编辑器减少轮询。会先做 appId 归属校验，再开始推送。事件：status/log/ping。"
    )
    public ResponseEntity<SseEmitter> events(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否推送日志（默认 true）") @RequestParam(defaultValue = "true") boolean withLog
    ) {
        // 关键：先校验归属，避免任何 ensure/getRunStatus 副作用
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }

        activityTracker.touch(userId);

        // 不设置超时（由前端主动断开/重连）
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("ws-sse-" + userId + "-" + appId);
            t.setDaemon(true);
            return t;
        });

        AtomicLong lastHeartbeatMs = new AtomicLong(System.currentTimeMillis());
        AtomicLong lastStatusHash = new AtomicLong(0L);

        // dev.log tail
        Path devLogPath = resolveDevLogPath(userId);
        AtomicLong logPos = new AtomicLong(0L);
        AtomicLong lastLogModified = new AtomicLong(0L);
        AtomicBoolean notifiedNoLog = new AtomicBoolean(false);

        Runnable tick = () -> {
            if (closed.get()) return;
            try {
                long now = System.currentTimeMillis();

                // 1) 心跳：更新 activity（SSE 长连接本身就代表“在线”）
                if (now - lastHeartbeatMs.get() >= 30_000L) {
                    lastHeartbeatMs.set(now);
                    try {
                        activityTracker.touch(userId);
                        if (funAiWorkspaceRunService != null) {
                            funAiWorkspaceRunService.touch(userId, now);
                        }
                    } catch (Exception ignore) {
                    }
                }

                // 2) status：每秒推一次（或变化即推）
                FunAiWorkspaceRunStatusResponse status = funAiWorkspaceService.getRunStatus(userId);
                long h = Objects.hash(status == null ? null : status.getState(),
                        status == null ? null : status.getAppId(),
                        status == null ? null : status.getPid(),
                        status == null ? null : status.getPreviewUrl(),
                        status == null ? null : status.getMessage());
                if (h != lastStatusHash.get()) {
                    lastStatusHash.set(h);
                    String json = objectMapper.writeValueAsString(Result.success(status));
                    emitter.send(SseEmitter.event().name("status").data(json, MediaType.APPLICATION_JSON));
                }

                // 3) log：增量读取（dev.log），避免重复传输
                if (withLog) {
                    pushLogDelta(emitter, devLogPath, logPos, lastLogModified, notifiedNoLog);
                }

                // 4) ping：保持连接
                emitter.send(SseEmitter.event().name("ping").data(String.valueOf(now), MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("events error: " + e.getMessage(), MediaType.TEXT_PLAIN));
                } catch (Exception ignore) {
                }
                safeClose(emitter, scheduler, closed);
            }
        };

        // 用 fixedDelay 避免 tick 发生卡顿时堆积（例如 docker exec 偶发变慢）
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(tick, 0L, 1000L, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> safeClose(emitter, scheduler, closed));
        emitter.onTimeout(() -> safeClose(emitter, scheduler, closed));
        emitter.onError((ex) -> safeClose(emitter, scheduler, closed));

        // 兜底：如果 emitter 被关闭，取消定时任务
        scheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                future.cancel(true);
            }
        }, 5, 5, TimeUnit.SECONDS);

        // nginx 反代 SSE 时容易被缓冲：建议通过 header 明确禁用缓冲/缓存
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.add("X-Accel-Buffering", "no");
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    @PostMapping("/log/clear")
    @Operation(
            summary = "清除 dev.log",
            description = "清空当前运行任务的 dev.log（宿主机文件 {hostRoot}/{userId}/run/dev.log）。" +
                    "由于 realtime 接口在安全配置中为白名单，这里会做 appId 归属校验，并校验 current.json 的 appId 防止误清其它应用日志。"
    )
    public Result<String> clearLog(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            if (userId == null || appId == null) {
                return Result.error("userId/appId 不能为空");
            }
            if (funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
                return Result.error("应用不存在或无权限操作");
            }
            activityTracker.touch(userId);
            funAiWorkspaceService.clearRunLog(userId, appId);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("clear log failed: " + e.getMessage());
        }
    }

    private Path resolveDevLogPath(Long userId) {
        try {
            String hostRoot = workspaceProperties == null ? null : workspaceProperties.getHostRoot();
            if (hostRoot == null || hostRoot.isBlank()) return null;
            String root = hostRoot.trim().replaceAll("^[\"']|[\"']$", "");
            return Paths.get(root, String.valueOf(userId), "run", "dev.log");
        } catch (Exception e) {
            return null;
        }
    }

    private void pushLogDelta(SseEmitter emitter, Path devLog, AtomicLong pos, AtomicLong lastModified, AtomicBoolean notifiedNoLog) {
        if (devLog == null) return;
        try {
            if (Files.notExists(devLog)) {
                if (notifiedNoLog != null && notifiedNoLog.compareAndSet(false, true)) {
                    emitter.send(SseEmitter.event().name("log").data("[log] dev.log 尚未生成（稍后会自动出现）\n", MediaType.TEXT_PLAIN));
                }
                return;
            }
            if (notifiedNoLog != null) {
                notifiedNoLog.set(false);
            }
            long lm = Files.getLastModifiedTime(devLog).toMillis();
            long prevLm = lastModified.get();
            if (lm != prevLm) {
                lastModified.set(lm);
            }

            long start = pos.get();
            long size = Files.size(devLog);
            // 日志可能被截断/清空：无论 mtime 是否变化，只要 pos 越界就重置
            if (start > size) {
                start = 0L;
                pos.set(0L);
            }
            if (start >= size) return;

            // 每次最多推 32KB，避免一次输出过大
            long max = 32 * 1024L;
            long end = Math.min(size, start + max);
            int len = (int) (end - start);
            byte[] buf = new byte[len];
            try (RandomAccessFile raf = new RandomAccessFile(devLog.toFile(), "r")) {
                raf.seek(start);
                int off = 0;
                while (off < len) {
                    int n = raf.read(buf, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off <= 0) return;
                if (off < len) {
                    byte[] smaller = new byte[off];
                    System.arraycopy(buf, 0, smaller, 0, off);
                    buf = smaller;
                    end = start + off;
                }
            }
            pos.set(end);
            String chunk = new String(buf, StandardCharsets.UTF_8);
            if (!chunk.isEmpty()) {
                emitter.send(SseEmitter.event().name("log").data(chunk, MediaType.TEXT_PLAIN));
            }
        } catch (Exception e) {
            // 不要因为日志读取失败而直接断开 SSE：先告知前端，等待下次 tick 重试
            try {
                emitter.send(SseEmitter.event().name("error").data("log tail error: " + e.getMessage(), MediaType.TEXT_PLAIN));
            } catch (Exception ignore2) {
            }
        }
    }

    private void safeClose(SseEmitter emitter, ScheduledExecutorService scheduler, AtomicBoolean closed) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
        try {
            scheduler.shutdownNow();
        } catch (Exception ignore) {
        }
    }
}


