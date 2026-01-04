package fun.ai.studio.controller;


import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.request.DeployFunAiAppRequest;
import fun.ai.studio.entity.request.UpdateFunAiAppBasicInfoRequest;
import fun.ai.studio.entity.response.FunAiAppDeployResponse;
import fun.ai.studio.enums.FunAiAppStatus;
import fun.ai.studio.service.FunAiAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @Value("${funai.siteBaseUrl:}")
    private String siteBaseUrl;

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
        return Result.success(apps);
    }

    /**
     * 获取单个应用信息
     * @param appId 应用ID
     * @return 应用信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取应用详情", description = "根据应用ID获取应用详情,appStatus: 0:空壳/草稿，1:已上传，2:部署中，3:可访问，4:部署失败，5:禁用")
    public Result<FunAiApp> getAppInfo(@Parameter(description = "用户ID", required = true) @RequestParam Long userId, @Parameter(description = "应用ID", required = true) @RequestParam Long appId) {
        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            return Result.error("应用不存在");
        }
        // 部署成功后给前端返回可访问路径
        if (app.getAppStatus() != null && app.getAppStatus() == FunAiAppStatus.READY.code()) {
            String base = (siteBaseUrl == null) ? "" : siteBaseUrl.trim();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (base.isEmpty()) {
                // 未配置时回退到当前请求域名
                // 注意：如果你后面有反向代理，建议配置 funai.siteBaseUrl
                // 例如 http://172.17.5.80:8080
                // 这里不使用 X-Forwarded-*，保持简单
                base = "http://localhost:8080";
            }
            app.setAccessUrl(base + "/fun-ai-app/" + userId + "/" + appId + "/");
        } else {
            app.setAccessUrl(null);
        }
        return Result.success(app);
    }


    /**
     * 删除应用
     * @param userId 用户ID
     * @param appId 应用ID
     * @return 删除结果
     */
    @GetMapping ("/delete")
    @Operation(summary = "删除应用", description = "根据应用ID删除应用")
    public Result<String> deleteApp(@Parameter(description = "用户ID", required = true) @RequestParam Long userId, @Parameter(description = "应用ID", required = true) @RequestParam Long appId) {
        try {
            boolean deleted = funAiAppService.deleteApp(appId, userId);
            if (!deleted) {
                return Result.error("删除应用失败");
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
     * 上传应用文件（zip压缩包）
     * @param userId 用户ID
     * @param appId 应用ID
     * @param file 上传的zip文件
     * @return 上传结果，包含文件保存路径
     */
    @PostMapping("/upload")
    @Deprecated
    @Operation(summary = "[Deprecated] 上传应用文件（旧链路）", description = "旧链路：上传zip压缩包到指定应用文件夹。全量 workspace 场景请使用 /api/fun-ai/workspace/apps/upload", deprecated = true)
    public Result<String> uploadAppFile(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "zip文件", required = true) @RequestParam("file") MultipartFile file) {
        try {
            return Result.error("该接口已废弃：请使用 /api/fun-ai/workspace/apps/upload");
        } catch (IllegalArgumentException e) {
            logger.warn("文件上传失败: userId={}, appId={}, error={}", userId, appId, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("文件上传失败: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("文件上传失败: " + e.getMessage());
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
     * 部署应用
     * 校验：
     * - 必须已上传 zip
     * - 仅当 appStatus == 1 时允许发布/部署
     */
    @PostMapping("/deploy")
    @Deprecated
    @Operation(summary = "[Deprecated] 部署应用（旧链路）", description = "旧链路：解压 + npm build + /fun-ai-app 静态站点。全量 workspace 场景请使用 workspace run/start", deprecated = true)
    public Result<FunAiAppDeployResponse> deployApp(@RequestBody DeployFunAiAppRequest req) {
        try {
            return Result.error("该接口已废弃：请使用 /api/fun-ai/workspace/run/start");
        } catch (IllegalArgumentException e) {
            logger.warn("部署应用失败: req={}, error={}", req, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            logger.error("部署应用失败: req={}, error={}", req, e.getMessage(), e);
            return Result.error("部署应用失败: " + e.getMessage());
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

    /**
     * 下载指定应用“最新的zip代码包”
     */
    @GetMapping("/download-app")
    @Deprecated
    @Operation(summary = "[Deprecated] 下载应用最新zip代码包（旧链路）", description = "旧链路：下载应用目录下上传的 zip。全量 workspace 场景请使用 /api/fun-ai/workspace/apps/download", deprecated = true)
    public ResponseEntity<Resource> downloadLatestZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            return ResponseEntity.status(HttpStatus.GONE).build();
        } catch (IllegalArgumentException e) {
            // 业务类错误（无权限/不存在/未上传）
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("下载zip失败: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
