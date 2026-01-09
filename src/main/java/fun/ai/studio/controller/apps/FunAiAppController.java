package fun.ai.studio.controller.apps;


import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiWorkspaceRun;
import fun.ai.studio.entity.request.UpdateFunAiAppBasicInfoRequest;
import fun.ai.studio.entity.response.FunAiOpenEditorResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.service.FunAiWorkspaceRunService;
import fun.ai.studio.workspace.WorkspaceProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.List;

/**
 * AI应用控制器
 */
@RestController
@RequestMapping("/api/fun-ai/app")
@Tag(name = "Fun AI 应用管理", description = "AI应用的增删改查接口")
@CrossOrigin(origins = {
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://172.17.5.80:5173",
        "http://172.17.5.80:8080"
}, allowCredentials = "true")
public class FunAiAppController {

    private static final Logger logger = LoggerFactory.getLogger(FunAiAppController.class);

    @Autowired
    private FunAiAppService funAiAppService;

    @Autowired(required = false)
    private FunAiWorkspaceRunService funAiWorkspaceRunService;

    @Autowired(required = false)
    private WorkspaceProperties workspaceProperties;

    @Autowired(required = false)
    private FunAiWorkspaceService funAiWorkspaceService;

    private String sanitizePath(String p) {
        if (p == null) return null;
        return p.trim().replaceAll("^[\"']|[\"']$", "");
    }

    private boolean detectPackageJson(Path appDir) {
        if (appDir == null || !Files.isDirectory(appDir)) return false;
        // 优先检查根目录（最快）
        if (Files.exists(appDir.resolve("package.json"))) return true;
        // 兼容 zip 顶层多一层目录的情况：maxDepth=2
        try (Stream<Path> s = Files.find(appDir, 2, (p, a) -> a.isRegularFile() && "package.json".equals(p.getFileName().toString()))) {
            return s.findFirst().isPresent();
        } catch (Exception ignore) {
            return false;
        }
    }

    private void fillRuntimeFields(Long userId, List<FunAiApp> apps) {
        if (apps == null || apps.isEmpty()) return;
        try {
            // 1) workspace 运行态（last-known）
            FunAiWorkspaceRun run = (funAiWorkspaceRunService == null) ? null : funAiWorkspaceRunService.getByUserId(userId);

            // 2) 代码是否已同步到 workspace（无副作用：仅检查宿主机目录）
            String hostRoot = workspaceProperties == null ? null : sanitizePath(workspaceProperties.getHostRoot());
            Path userAppsDir = (hostRoot == null || hostRoot.isBlank()) ? null : Paths.get(hostRoot, String.valueOf(userId), "apps");

            for (FunAiApp app : apps) {
                if (app == null) continue;
                if (run != null) {
                    // 容器状态对该用户下所有 app 都相同
                    app.setWorkspaceContainerStatus(run.getContainerStatus());
                }

                // 只有“当前运行的 app”才填 runState/previewUrl/log
                if (run != null && run.getAppId() != null && app.getId() != null && run.getAppId().equals(app.getId())) {
                    app.setWorkspaceRunState(run.getRunState());
                    app.setWorkspacePreviewUrl(run.getPreviewUrl());
                    app.setWorkspaceLogPath(run.getLogPath());
                    app.setWorkspaceLastError(run.getLastError());
                } else {
                    app.setWorkspaceRunState(null);
                    app.setWorkspacePreviewUrl(null);
                    app.setWorkspaceLogPath(null);
                    app.setWorkspaceLastError(null);
                }

                // 项目目录存在性与 package.json 检测
                if (userAppsDir != null && app.getId() != null) {
                    Path appDir = userAppsDir.resolve(String.valueOf(app.getId()));
                    boolean hasDir = Files.isDirectory(appDir);
                    app.setWorkspaceHasProjectDir(hasDir);
                    app.setWorkspaceHasPackageJson(hasDir && detectPackageJson(appDir));
                } else {
                    app.setWorkspaceHasProjectDir(null);
                    app.setWorkspaceHasPackageJson(null);
                }
            }
        } catch (Exception e) {
            logger.warn("fill runtime fields failed: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 创建应用
     * @param userId 用户ID
     * @return 创建后的应用信息
     */
    @Operation(summary = "创建应用", description = "创建新的AI应用")
    @GetMapping(path="/create")
    public Result<FunAiApp> createApp(@Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        try {
            FunAiApp createdApp = funAiAppService.createAppWithValidation(userId);
            return Result.success(createdApp);
        }catch (Exception e) {
            logger.error("创建应用失败: userId={}, error={}", userId, e.getMessage(), e);
            return Result.error("创建应用失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前用户的应用列表
     * @return 应用列表
     */
    @GetMapping(path = "/list")
    @Operation(summary = "获取当前用户的所有应用", description = "获取当前用户的所有应用")
    public Result<List<FunAiApp>> getApps(@Parameter(description = "用户ID", required = true) @RequestParam Long userId) {
        List<FunAiApp> apps = funAiAppService.getAppsByUserId(userId);
        // 与容器运行态结合：补充 last-known runtime 字段（不改变 appStatus 的业务含义）
        fillRuntimeFields(userId, apps);
        return Result.success(apps);
    }

    /**
     * 获取单个应用信息
     * @param appId 应用ID
     * @return 应用信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取应用详情", description = "根据应用ID获取应用详情,appStatus: 0:空壳/草稿，1:已上传，2:部署中，3:可访问，4:部署失败，5:禁用")
    public Result<FunAiApp> getAppInfo(@Parameter(description = "用户ID", required = true) @RequestParam Long userId,
                                       @Parameter(description = "应用ID", required = true) @RequestParam Long appId) {
        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            return Result.error("应用不存在");
        }
        // 与容器运行态结合：补充 last-known runtime 字段
        fillRuntimeFields(userId, List.of(app));

        // 旧链路的 /fun-ai-app 静态站点已废弃；workspace 运行时将 accessUrl 指向 previewUrl（/ws/{userId}/）
        if (app.getWorkspacePreviewUrl() != null && !app.getWorkspacePreviewUrl().isBlank()) {
            app.setAccessUrl(app.getWorkspacePreviewUrl());
        } else {
            app.setAccessUrl(null);
        }
        return Result.success(app);
    }

    @PostMapping("/open-editor")
    @Operation(
            summary = "打开在线编辑器（聚合接口）",
            description = "对齐点：代码在容器内可见（通过 hostRoot bind-mount 到 /workspace）。流程：校验 app 归属 → ensure app dir（会确保容器运行）→ 检测 package.json（maxDepth=2）→ 若存在则自动触发 workspace/run/start（非阻塞）并返回 runStatus；否则仅返回 run/status 引导上传/新建。"
    )
    public Result<FunAiOpenEditorResponse> openEditor(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            if (userId == null || appId == null) {
                return Result.error("userId/appId 不能为空");
            }
            if (funAiWorkspaceService == null) {
                return Result.error("workspace 功能未启用");
            }

            FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
            if (app == null) {
                return Result.error("应用不存在或无权限操作");
            }

            // 关键：同一用户同时只能预览一个应用（/ws/{userId}/ 是用户级入口）
            // 若当前运行的是其它 app（A），而用户打开的是 B（可能还未创建/无 package.json），
            // 为避免前端默认打开预览时“看到的还是 A”，这里先 stop 当前 run，确保预览不会误指向旧应用。
            try {
                FunAiWorkspaceRunStatusResponse cur = funAiWorkspaceService.getRunStatus(userId);
                String st = cur == null ? null : cur.getState();
                Long runningAppId = cur == null ? null : cur.getAppId();
                boolean running = st != null && ("RUNNING".equalsIgnoreCase(st) || "STARTING".equalsIgnoreCase(st));
                if (running && runningAppId != null && !runningAppId.equals(appId)) {
                    funAiWorkspaceService.stopRun(userId);
                }
            } catch (Exception ignore) {
            }

            // 关键：ensureAppDir 内部已做归属校验，且会确保容器运行并创建宿主机目录（目录挂载到容器 /workspace）
            FunAiWorkspaceProjectDirResponse dir = funAiWorkspaceService.ensureAppDir(userId, appId);
            Path hostAppDir = Paths.get(dir.getHostAppDir());
            boolean hasPkg = detectPackageJson(hostAppDir);

            FunAiWorkspaceRunStatusResponse runStatus = hasPkg
                    ? funAiWorkspaceService.startDev(userId, appId)   // 非阻塞：一般返回 STARTING
                    : funAiWorkspaceService.getRunStatus(userId);      // 通常为 IDLE

            // 补充 runtime 字段（previewUrl/runState 等）
            fillRuntimeFields(userId, List.of(app));
            // 只在“当前 run 的 appId 与本次打开的 appId 一致”时，才给前端返回预览地址
            if (runStatus != null
                    && runStatus.getAppId() != null
                    && runStatus.getAppId().equals(appId)
                    && runStatus.getPreviewUrl() != null
                    && !runStatus.getPreviewUrl().isBlank()) {
                app.setAccessUrl(runStatus.getPreviewUrl());
            } else {
                app.setAccessUrl(null);
            }

            FunAiOpenEditorResponse resp = new FunAiOpenEditorResponse();
            resp.setUserId(userId);
            resp.setAppId(appId);
            resp.setApp(app);
            resp.setProjectDir(dir);
            resp.setHasPackageJson(hasPkg);
            resp.setRunStatus(runStatus);
            resp.setMessage(hasPkg
                    ? "已检测到 package.json，已触发启动；请轮询 /api/fun-ai/workspace/run/status 等待 RUNNING"
                    : "未检测到 package.json：请先上传 zip 或在编辑器中新建项目文件");
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            logger.warn("open editor failed: userId={}, appId={}, error={}", userId, appId, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("open editor failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("open editor failed: " + e.getMessage());
        }
    }


    /**
     * 删除应用
     * @param userId 用户ID
     * @param appId 应用ID
     * @return 删除结果
     */
    @GetMapping ("/delete")
    @Operation(summary = "删除应用", description = "根据应用ID删除应用")
    public Result<String> deleteApp(@Parameter(description = "用户ID", required = true) @RequestParam Long userId,
                                    @Parameter(description = "应用ID", required = true) @RequestParam Long appId) {
        try {
            boolean deleted = funAiAppService.deleteApp(appId, userId);
            if (!deleted) {
                return Result.error("删除应用失败");
            }
            // workspace 清理（宿主机 /data/funai/workspaces/{userId}/apps/{appId}）
            // 注意：deleteApp 已做归属校验，这里不再重复校验，且清理失败不影响删除结果
            try {
                if (funAiWorkspaceService != null && workspaceProperties != null && workspaceProperties.isEnabled()) {
                    funAiWorkspaceService.cleanupWorkspaceOnAppDeleted(userId, appId);
                }
            } catch (Exception e) {
                logger.warn("cleanup workspace after delete app failed: userId={}, appId={}, err={}", userId, appId, e.getMessage());
            }
            return Result.success("删除应用成功");
        } catch (IllegalArgumentException e) {
            logger.warn("删除应用失败: userId={}, appId={}, error={}", userId, appId, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("删除应用失败: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("删除应用失败: " + e.getMessage());
        }
    }

    /**
     * 修改应用基础信息（appName/appDescription/appType）
     * - appName：同一用户下不可重名
     */
    @PostMapping("/update-basic")
    @Operation(summary = "修改应用基础信息", description = "允许修改 appName/appDescription/appType，其中 appName 需同一用户下唯一")
    public Result<FunAiApp> updateBasicInfo(@RequestBody UpdateFunAiAppBasicInfoRequest req) {
        try {
            FunAiApp updated = funAiAppService.updateBasicInfo(
                    req.getUserId(),
                    req.getAppId(),
                    req.getAppName(),
                    req.getAppDescription(),
                    req.getAppType()
            );
            return Result.success("修改成功", updated);
        } catch (IllegalArgumentException e) {
            logger.warn("修改应用基础信息失败: req={}, error={}", req, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("修改应用基础信息失败: req={}, error={}", req, e.getMessage(), e);
            return Result.error("修改应用基础信息失败: " + e.getMessage());
        }
    }

    /**
     * 手动修正应用状态（用于服务器异常导致状态不正确时的兜底操作）
     */
    @PostMapping("/update-status")
    @Operation(summary = "手动修改应用状态", description = "仅用于异常兜底：强制修改指定应用的 appStatus（会校验应用归属；非失败状态会清空 lastDeployError）")
    public Result<FunAiApp> updateAppStatus(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "应用状态（0=空壳/草稿；1=已上传；2=部署中；3=可访问；4=部署失败；5=禁用）", required = true)
            @RequestParam Integer appStatus
    ) {
        try {
            if (userId == null || appId == null || appStatus == null) {
                return Result.error("userId/appId/appStatus 不能为空");
            }
            // 简单合法性校验：当前枚举定义为 0..5
            if (appStatus < FunAiAppStatus.CREATED.code() || appStatus > FunAiAppStatus.DISABLED.code()) {
                return Result.error("appStatus 非法");
            }

            FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
            if (app == null) {
                return Result.error("应用不存在或无权限操作");
            }

            app.setAppStatus(appStatus);
            // 非失败状态下清空上次失败原因，避免前端一直显示旧错误
            if (appStatus != FunAiAppStatus.FAILED.code()) {
                app.setLastDeployError(null);   
            }

            boolean ok = funAiAppService.updateById(app);
            if (!ok) {
                return Result.error("修改应用状态失败");
            }
            FunAiApp latest = funAiAppService.getAppByIdAndUserId(appId, userId);
            return Result.success("修改成功", latest == null ? app : latest);
        } catch (Exception e) {
            logger.error("手动修改应用状态失败: userId={}, appId={}, appStatus={}, error={}", userId, appId, appStatus, e.getMessage(), e);
            return Result.error("修改应用状态失败: " + e.getMessage());
        }
    }

}
