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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
            @Parameter(description = "是否推送日志（默认 true）") @RequestParam(defaultValue = "true") boolean withLog,
            @Parameter(description = "可选：仅推送指定类型日志（BUILD/INSTALL/PREVIEW）") @RequestParam(required = false) String type
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
        AtomicLong lastKeepAliveMs = new AtomicLong(System.currentTimeMillis());

        // log tail：根据 current.json 的 logPath 动态切换（每次任务一个独立日志文件）
        AtomicReference<String> currentLogPath = new AtomicReference<>(null);
        AtomicLong logPos = new AtomicLong(0L);
        AtomicLong lastLogModified = new AtomicLong(0L);
        AtomicBoolean notifiedNoLog = new AtomicBoolean(false);
        String expectType = normalizeUiType(type);

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

                // 3) log：增量读取（按 current.json.logPath），避免 build/install/preview 混流
                if (withLog) {
                    String lp = status == null ? null : status.getLogPath();
                    Path hostLog = resolveHostLogPath(userId, lp);
                    String prev = currentLogPath.get();
                    String nowLp = lp == null ? null : lp.trim();
                    if (!Objects.equals(prev, nowLp)) {
                        currentLogPath.set(nowLp);
                        // 切换日志文件：重置读取位置，并告知前端这段日志属于哪个任务(type)
                        logPos.set(0L);
                        lastLogModified.set(0L);
                        notifiedNoLog.set(false);
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("userId", userId);
                        meta.put("appId", status == null ? null : status.getAppId());
                        meta.put("type", normalizeUiType(status == null ? null : status.getType()));
                        meta.put("state", status == null ? null : status.getState());
                        meta.put("logPath", nowLp);
                        emitter.send(SseEmitter.event().name("log_meta").data(objectMapper.writeValueAsString(meta), MediaType.APPLICATION_JSON));
                    }
                    String curType = normalizeUiType(status == null ? null : status.getType());
                    // 若前端指定了 type，则只推送该类型的日志（status/ping 仍然推送）
                    if (expectType == null || Objects.equals(expectType, curType)) {
                        pushLogDelta(emitter, hostLog, logPos, lastLogModified, notifiedNoLog);
                    }
                }

                // 4) keep-alive：用 SSE comment 保持连接（前端不会收到 ping 事件）
                if (now - lastKeepAliveMs.get() >= 25_000L) {
                    lastKeepAliveMs.set(now);
                    // 注释行格式：": xxx\n\n"（EventSource 不会触发 message 事件）
                    emitter.send(":" + now + "\n\n");
                }
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
            summary = "清除当前运行任务日志",
            description = "清空当前运行任务的日志文件（优先使用 current.json.logPath 指向的文件；兼容旧逻辑回退 dev.log）。" +
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

    private Path resolveHostLogPath(Long userId, String containerLogPath) {
        try {
            String hostRoot = workspaceProperties == null ? null : workspaceProperties.getHostRoot();
            if (hostRoot == null || hostRoot.isBlank()) return null;
            String root = hostRoot.trim().replaceAll("^[\"']|[\"']$", "");
            Path hostUserDir = Paths.get(root, String.valueOf(userId));
            if (containerLogPath == null || containerLogPath.isBlank()) {
                // 兼容旧逻辑：dev.log
                return hostUserDir.resolve("run").resolve("dev.log");
            }
            String workdir = workspaceProperties == null ? null : workspaceProperties.getContainerWorkdir();
            if (workdir == null || workdir.isBlank()) workdir = "/workspace";
            String p = containerLogPath.trim();
            if (!p.startsWith(workdir)) {
                // 不符合预期则回退到 dev.log
                return hostUserDir.resolve("run").resolve("dev.log");
            }
            String rel = p.substring(workdir.length());
            while (rel.startsWith("/")) rel = rel.substring(1);
            if (rel.isEmpty()) return hostUserDir.resolve("run").resolve("dev.log");
            return hostUserDir.resolve(rel);
        } catch (Exception e) {
            return null;
        }
    }

    private void pushLogDelta(SseEmitter emitter, Path logFile, AtomicLong pos, AtomicLong lastModified, AtomicBoolean notifiedNoLog) {
        if (logFile == null) return;
        try {
            if (Files.notExists(logFile)) {
                if (notifiedNoLog != null && notifiedNoLog.compareAndSet(false, true)) {
                    emitter.send(SseEmitter.event().name("log").data("[log] 日志文件尚未生成（稍后会自动出现）\n", MediaType.TEXT_PLAIN));
                }
                return;
            }
            if (notifiedNoLog != null) {
                notifiedNoLog.set(false);
            }
            long lm = Files.getLastModifiedTime(logFile).toMillis();
            long prevLm = lastModified.get();
            if (lm != prevLm) {
                lastModified.set(lm);
            }

            long start = pos.get();
            long size = Files.size(logFile);
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
            try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
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

    /**
     * 前端只关心 BUILD/INSTALL/PREVIEW：这里把后端运行态 type（DEV/START/BUILD/INSTALL）映射成 UI type。
     * - START -> PREVIEW
     * - BUILD/INSTALL -> 原样
     * - DEV -> PREVIEW（若你们前端没有 DEV 概念，可把 dev 也当作预览入口）
     */
    private String normalizeUiType(String t) {
        if (t == null) return null;
        String s = t.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if ("START".equals(s)) return "PREVIEW";
        if ("DEV".equals(s)) return "PREVIEW";
        if ("BUILD".equals(s)) return "BUILD";
        if ("INSTALL".equals(s)) return "INSTALL";
        if ("PREVIEW".equals(s)) return "PREVIEW";
        return s;
    }
}


