package fun.ai.studio.controller;

import fun.ai.studio.common.Result;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.service.FunAiWorkspaceRunService;
import fun.ai.studio.entity.request.FunAiWorkspaceFileWriteRequest;
import fun.ai.studio.entity.request.FunAiWorkspacePathRequest;
import fun.ai.studio.entity.request.FunAiWorkspaceRenameRequest;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import fun.ai.studio.workspace.WorkspaceActivityTracker;
import fun.ai.studio.workspace.WorkspaceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Workspace 控制器（阶段B：先打通持久化目录 + 容器挂载）
 */
@RestController
@RequestMapping("/api/fun-ai/workspace")
@Tag(name = "Fun AI Workspace 容器", description = "用户在线开发 workspace（容器）管理接口")

public class FunAiWorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceController.class);

    private final FunAiWorkspaceService funAiWorkspaceService;
    private final FunAiWorkspaceRunService funAiWorkspaceRunService;
    private final WorkspaceActivityTracker activityTracker;
    private final fun.ai.studio.service.impl.FunAiWorkspaceServiceImpl workspaceServiceImpl;
    private final WorkspaceProperties workspaceProperties;

    public FunAiWorkspaceController(FunAiWorkspaceService funAiWorkspaceService, WorkspaceActivityTracker activityTracker,
                                    fun.ai.studio.service.impl.FunAiWorkspaceServiceImpl workspaceServiceImpl,
                                    WorkspaceProperties workspaceProperties,
                                    FunAiWorkspaceRunService funAiWorkspaceRunService) {
        this.funAiWorkspaceService = funAiWorkspaceService;
        this.activityTracker = activityTracker;
        this.workspaceServiceImpl = workspaceServiceImpl;
        this.workspaceProperties = workspaceProperties;
        this.funAiWorkspaceRunService = funAiWorkspaceRunService;
    }

    @PostMapping("/ensure")
    @Operation(summary = "创建宿主机目录并启动workspace(容器)", description = "创建宿主机目录并确保 ws-u-{userId} 容器运行，挂载到 /workspace")
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
    @Operation(summary = "查询 workspace(容器) 状态", description = "返回容器状态、端口、宿主机目录 容器状态值：NOT_CREATED / RUNNING / EXITED / UNKNOWN")
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

    @GetMapping("/internal/nginx/port")
    @Operation(summary = "（内部）nginx 反代查询端口", description = "供 nginx auth_request 使用：根据 userId 返回 X-WS-Port 头。不做 ensure/start，避免副作用。")
    public ResponseEntity<Void> nginxPort(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            HttpServletRequest request
    ) {
        try {
            // 优先使用共享密钥（解决 auth_request 场景下 remoteAddr 可能不是 127.0.0.1 的问题）
            String required = workspaceProperties == null ? null : workspaceProperties.getNginxAuthToken();
            String tokenHeader = request == null ? null : request.getHeader("X-WS-Token");
            String tokenParam = request == null ? null : request.getParameter("token");
            if (StringUtils.hasText(required)) {
                boolean ok = required.equals(tokenHeader) || required.equals(tokenParam);
                if (!ok) {
                    String remote = request == null ? null : request.getRemoteAddr();
                    log.warn("nginx port unauthorized: userId={}, remoteAddr={}, hasHeader={}, hasParam={}",
                            userId, remote, StringUtils.hasText(tokenHeader), StringUtils.hasText(tokenParam));
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                }
            } else {
                // 若未配置密钥，则退化为仅允许同机调用（localhost）
                String remote = request == null ? null : request.getRemoteAddr();
                if (remote == null || !(remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"))) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
            }
            Integer port = workspaceServiceImpl.getHostPortForNginx(userId);
            if (port == null || port <= 0) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.noContent()
                    .header("X-WS-Port", String.valueOf(port))
                    .build();
        } catch (Exception e) {
            log.warn("nginx port lookup failed: userId={}, error={}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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

    @GetMapping("/apps/tree")
    @Operation(summary = "获取文件树", description = "返回 apps/{appId} 下的目录树（默认忽略 node_modules/.git/dist/build/.next/target）")
    public Result<FunAiWorkspaceFileTreeResponse> tree(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径（默认 .）") @RequestParam(required = false) String path,
            @Parameter(description = "最大递归深度（默认 6）") @RequestParam(required = false) Integer maxDepth,
            @Parameter(description = "最大节点数（默认 5000）") @RequestParam(required = false) Integer maxEntries
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.listFileTree(userId, appId, path, maxDepth, maxEntries));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("list file tree failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("list file tree failed: " + e.getMessage());
        }
    }

    @GetMapping("/apps/file")
    @Operation(summary = "读取文件内容", description = "读取 apps/{appId} 下指定文件（UTF-8 文本，限制 2MB）")
    public Result<FunAiWorkspaceFileReadResponse> readFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.readFileContent(userId, appId, path));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("read file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return Result.error("read file failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/file")
    @Operation(summary = "写入文件内容", description = "写入 apps/{appId} 下指定文件（UTF-8 文本），支持 expectedLastModifiedMs 乐观锁")
    public Result<FunAiWorkspaceFileReadResponse> writeFile(@RequestBody FunAiWorkspaceFileWriteRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean createParents = req.getCreateParents() == null || req.getCreateParents();
            return Result.success(funAiWorkspaceService.writeFileContent(
                    req.getUserId(), req.getAppId(), req.getPath(), req.getContent(), createParents, req.getExpectedLastModifiedMs()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("write file failed: error={}", e.getMessage(), e);
            return Result.error("write file failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/mkdir")
    @Operation(summary = "创建目录", description = "创建 apps/{appId} 下目录")
    public Result<String> mkdir(@RequestBody FunAiWorkspacePathRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean createParents = req.getCreateParents() == null || req.getCreateParents();
            funAiWorkspaceService.createDirectory(req.getUserId(), req.getAppId(), req.getPath(), createParents);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("mkdir failed: error={}", e.getMessage(), e);
            return Result.error("mkdir failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/delete")
    @Operation(summary = "删除路径", description = "删除 apps/{appId} 下的文件/目录（递归）")
    public Result<String> delete(@RequestBody FunAiWorkspacePathRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            funAiWorkspaceService.deletePath(req.getUserId(), req.getAppId(), req.getPath());
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("delete failed: error={}", e.getMessage(), e);
            return Result.error("delete failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/move")
    @Operation(summary = "移动/重命名", description = "在 apps/{appId} 内移动/重命名文件或目录")
    public Result<String> move(@RequestBody FunAiWorkspaceRenameRequest req) {
        try {
            if (req == null) return Result.error("请求不能为空");
            activityTracker.touch(req.getUserId());
            boolean overwrite = req.getOverwrite() != null && req.getOverwrite();
            funAiWorkspaceService.movePath(req.getUserId(), req.getAppId(), req.getFromPath(), req.getToPath(), overwrite);
            return Result.success("ok");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("move failed: error={}", e.getMessage(), e);
            return Result.error("move failed: " + e.getMessage());
        }
    }

    @PostMapping("/apps/upload-file")
    @Operation(summary = "上传单文件", description = "上传单文件到 apps/{appId} 下指定路径")
    public Result<FunAiWorkspaceFileReadResponse> uploadFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path,
            @Parameter(description = "文件", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "是否覆盖（默认 true）") @RequestParam(defaultValue = "true") boolean overwrite,
            @Parameter(description = "是否创建父目录（默认 true）") @RequestParam(defaultValue = "true") boolean createParents
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(funAiWorkspaceService.uploadFile(userId, appId, path, file, overwrite, createParents));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("upload file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return Result.error("upload file failed: " + e.getMessage());
        }
    }

    @GetMapping("/apps/download-file")
    @Operation(summary = "下载单文件", description = "下载 apps/{appId} 下指定文件")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "相对路径", required = true) @RequestParam String path
    ) {
        try {
            activityTracker.touch(userId);
            Path filePath = funAiWorkspaceService.downloadFile(userId, appId, path);
            String filename = filePath.getFileName() == null ? "file" : filePath.getFileName().toString();

            StreamingResponseBody body = os -> {
                try (InputStream is = Files.newInputStream(filePath)) {
                    is.transferTo(os);
                    os.flush();
                }
            };

            ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("download file failed: userId={}, appId={}, path={}, error={}", userId, appId, path, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/run/start")
    @Operation(summary = "启动应用（同一时间只允许运行一个应用）", description = "在 workspace 容器内启动 npm run dev，并写入 /workspace/run/current.json 与 dev.pid")
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
    @Operation(summary = "停止当前运行应用", description = "kill 进程组并清理 /workspace/run/dev.pid 与 current.json")
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
    @Operation(summary = "查询当前应用运行状态", description = "读取 /workspace/run/current.json 并验证 pid/端口是否就绪 应用运行态：IDLE / STARTING / RUNNING / DEAD（RUNNING 时返回 previewUrl）")
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
            if (funAiWorkspaceRunService != null) {
                funAiWorkspaceRunService.touch(userId, System.currentTimeMillis());
            }
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


