package fun.ai.studio.controller;

import fun.ai.studio.common.Result;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceHeartbeatResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import fun.ai.studio.workspace.WorkspaceActivityTracker;

/**
 * Workspace 控制器（阶段B：先打通持久化目录 + 容器挂载）
 */
@RestController
@RequestMapping("/api/fun-ai/workspace")
@Tag(name = "Fun AI Workspace 容器", description = "用户在线开发 workspace（容器）管理接口")

public class FunAiWorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceController.class);

    private final FunAiWorkspaceService funAiWorkspaceService;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceController(FunAiWorkspaceService funAiWorkspaceService, WorkspaceActivityTracker activityTracker) {
        this.funAiWorkspaceService = funAiWorkspaceService;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/ensure")
    @Operation(summary = "创建宿主机目录并启动", description = "创建宿主机目录并确保 ws-u-{userId} 容器运行，挂载到 /workspace")
    public Result<FunAiWorkspaceInfoResponse> ensure(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.ensureWorkspace(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure workspace failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("ensure workspace failed: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    @Operation(summary = "查询 workspace 状态", description = "返回容器状态、端口、宿主机目录 容器状态值：NOT_CREATED / RUNNING / EXITED / UNKNOWN")
    public Result<FunAiWorkspaceStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.getStatus(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get workspace status failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("get workspace status failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/ensure-dir")
    @Operation(summary = "确保应用目录存在", description = "确保 /workspace/apps/<appId> 的宿主机目录存在（会先 ensure workspace）")
    public Result<FunAiWorkspaceProjectDirResponse> ensureAppDir(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.ensureAppDir(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("ensure app dir failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("ensure app dir failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/upload")
    @Operation(summary = "上传应用 zip 并解压到 workspace 应用目录", description = "上传 zip 后解压到 {hostRoot}/{userId}/apps/{appId}，容器内可见 /workspace/apps/{appId}")
    public Result<FunAiWorkspaceProjectDirResponse> uploadAppZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "zip文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否覆盖已存在目录（默认 true）") @RequestParam(defaultValue = "true") boolean overwrite
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.uploadAppZip(userId, appId, file, overwrite));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("upload app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("upload app zip failed: " + e.getMessage());
        }
    }

    @PostMapping("/run/start")
    @Operation(summary = "启动 dev server（同一时间只允许运行一个应用）", description = "在 workspace 容器内启动 npm run dev，并写入 /workspace/run/current.json 与 dev.pid")
    public Result<FunAiWorkspaceRunStatusResponse> startDev(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.startDev(userId, appId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("start dev failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("start dev failed: " + e.getMessage());
        }
    }

    @PostMapping("/run/stop")
    @Operation(summary = "停止当前运行任务", description = "kill 进程组并清理 /workspace/run/dev.pid 与 current.json")
    public Result<FunAiWorkspaceRunStatusResponse> stopRun(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.stopRun(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("stop run failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("stop run failed: " + e.getMessage());
        }
    }

    @GetMapping("/run/status")
    @Operation(summary = "查询当前运行状态", description = "读取 /workspace/run/current.json 并验证 pid/端口是否就绪 应用运行态：IDLE / STARTING / RUNNING / DEAD（RUNNING 时返回 previewUrl）")
    public Result<FunAiWorkspaceRunStatusResponse> runStatus(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.getRunStatus(userId));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get run status failed: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("get run status failed: " + e.getMessage());
        }
    }

    @GetMapping("/apps/download")
    @Operation(summary = "下载应用目录（zip）", description = "将 {hostRoot}/{userId}/apps/{appId} 打包为 zip 并下载（默认排除 node_modules/.git/dist 等）")
    public ResponseEntity<StreamingResponseBody> downloadAppZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否包含 node_modules（默认 false）") @RequestParam(defaultValue = "false") boolean includeNodeModules
    ) {
        try {
            activityTracker.touch(userId);
            String filename = "app_" + appId + ".zip";

            // 通过 ensureAppDir 拿到宿主机路径（不额外落临时 zip，避免卡住/堆积）
            Path hostAppDir = Paths.get(funAiWorkspaceService.ensureAppDir(userId, appId).getHostAppDir());

            Set<String> excludes = includeNodeModules
                    ? Set.of(".git", "dist", "build", ".next", "target")
                    : Set.of("node_modules", ".git", "dist", "build", ".next", "target");

            StreamingResponseBody body = outputStream -> {
                // 边打包边输出，客户端会立即收到数据（体验比“先打包再返回”好）
                fun.ai.studio.workspace.ZipUtils.zipDirectory(hostAppDir, outputStream, excludes);
                outputStream.flush();
            };

            ContentDisposition disposition = ContentDisposition.attachment()
                    .filename(filename)
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("download app zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "workspace 心跳", description = "前端定时调用，更新 lastActiveAt，用于 idle 回收")
    public Result<FunAiWorkspaceHeartbeatResponse> heartbeat(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId
    ) {
        try {
            activityTracker.touch(userId);
            FunAiWorkspaceHeartbeatResponse resp = new FunAiWorkspaceHeartbeatResponse();
            resp.setUserId(userId);
            resp.setServerTimeMs(System.currentTimeMillis());
            resp.setMessage("ok");
            return Result.success(resp);
        } catch (Exception e) {
            return Result.error("heartbeat failed: " + e.getMessage());
        }
    }
}


