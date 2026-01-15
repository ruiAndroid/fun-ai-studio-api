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
import fun.ai.studio.workspace.WorkspaceNodeClient;
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

    @Autowired(required = false)
    private WorkspaceNodeClient workspaceNodeClient;

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
            // 双机模式：运行态/文件系统在 Workspace 开发服务器（大机）；API 服务器（小机）只聚合展示
            FunAiWorkspaceRunStatusResponse remoteStatus = null;
            if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                try {
                    remoteStatus = workspaceNodeClient.getRunStatus(userId);
                } catch (Exception ignore) {
                }
            }

            // 单机/兼容：保留 DB last-known（若存在）
            FunAiWorkspaceRun run = (funAiWorkspaceRunService == null) ? null : funAiWorkspaceRunService.getByUserId(userId);

            for (FunAiApp app : apps) {
                if (app == null) continue;
                if (run != null) {
                    // 容器状态对该用户下所有 app 都相同
                    app.setWorkspaceContainerStatus(run.getContainerStatus());
                }

                // 只有“当前运行的 app”才填 runState/previewUrl/log
                if (remoteStatus != null && remoteStatus.getAppId() != null && app.getId() != null && remoteStatus.getAppId().equals(app.getId())) {
                    app.setWorkspaceRunState(remoteStatus.getState());
                    app.setWorkspacePreviewUrl(remoteStatus.getPreviewUrl());
                    app.setWorkspaceLogPath(remoteStatus.getLogPath());
                    app.setWorkspaceLastError(remoteStatus.getMessage());
                } else if (run != null && run.getAppId() != null && app.getId() != null && run.getAppId().equals(app.getId())) {
                    // fallback：单机模式
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

                // 双机模式：API 服务器（小机）不再直接读宿主机目录（目录在 Workspace 开发服务器（大机））。
                // 如需展示，可后续增加“批量查询 hasPackageJson”接口或在列表页延迟加载。
                app.setWorkspaceHasProjectDir(null);
                app.setWorkspaceHasPackageJson(null);
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
            description = "对齐点：代码在容器内可见（通过 hostRoot bind-mount 到 /workspace）。流程：校验 app 归属 → ensure app dir（会确保容器运行）→ 检测 package.json（maxDepth=2）→ 返回 run/status（不自动启动；由前端按钮触发 build/preview）。"
    )
    public Result<FunAiOpenEditorResponse> openEditor(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            if (userId == null || appId == null) {
                return Result.error("userId/appId 不能为空");
            }
            boolean remoteEnabled = (workspaceNodeClient != null && workspaceNodeClient.isEnabled());
            boolean localEnabled = (funAiWorkspaceService != null && workspaceProperties != null && workspaceProperties.isEnabled());
            if (!remoteEnabled && !localEnabled) {
                return Result.error("workspace 功能未启用");
            }

            FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
            if (app == null) {
                return Result.error("应用不存在或无权限操作");
            }

            FunAiWorkspaceProjectDirResponse dir;
            boolean hasPkg;
            FunAiWorkspaceRunStatusResponse runStatus;

            // 双机模式：open-editor 需要的“目录/是否有 package.json/运行态”都从 Workspace 开发服务器（大机）获取
            if (remoteEnabled) {
                dir = workspaceNodeClient.ensureDir(userId, appId);
                hasPkg = workspaceNodeClient.hasPackageJson(userId, appId);
                runStatus = workspaceNodeClient.getRunStatus(userId);
            } else {
                // 单机 fallback：本机 ensure + 本机磁盘探测
                dir = funAiWorkspaceService.ensureAppDir(userId, appId);
                Path hostAppDir = Paths.get(dir.getHostAppDir());
                hasPkg = detectPackageJson(hostAppDir);
                // 不自动启动（避免与 WS 终端自由命令并发导致进程错乱）
                runStatus = funAiWorkspaceService.getRunStatus(userId);
            }

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
                    ? "已检测到 package.json：请点击“构建/预览”按钮触发 npm run build / npm run start；当前不会自动启动"
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
            String cleanupWarn = null;
            try {
                if (workspaceNodeClient != null && workspaceNodeClient.isEnabled()) {
                    // 双机模式：通知 Workspace 开发服务器（大机）清理 workspace app 目录（与 API 服务器（小机）funai.workspace.enabled 无关）
                    workspaceNodeClient.cleanupOnAppDeleted(userId, appId);
                } else if (funAiWorkspaceService != null && workspaceProperties != null && workspaceProperties.isEnabled()) {
                    funAiWorkspaceService.cleanupWorkspaceOnAppDeleted(userId, appId);
                }
            } catch (Exception e) {
                cleanupWarn = e.getMessage();
                logger.warn("cleanup workspace after delete app failed: userId={}, appId={}, err={}", userId, appId, e.getMessage(), e);
            }
            if (cleanupWarn != null && !cleanupWarn.isBlank()) {
                return Result.success("删除应用成功（磁盘目录清理失败：" + cleanupWarn + "）");
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
