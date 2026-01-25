package fun.ai.studio.controller.deploy;

import fun.ai.studio.common.Result;
import fun.ai.studio.config.DeployAcrProperties;
import fun.ai.studio.config.DeployGitProperties;
import fun.ai.studio.deploy.DeployClient;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.request.DeployJobCreateRequest;
import fun.ai.studio.service.FunAiAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fun-ai/deploy")
@Tag(name = "Deploy（控制面入口）", description = "用户/前端只访问 API 服务；API 服务再调用 deploy 控制面创建 Job。")
public class FunAiDeployController {

    private final DeployClient deployClient;
    private final FunAiAppService funAiAppService;
    private final DeployGitProperties deployGitProperties;
    private final DeployAcrProperties deployAcrProperties;
    private static final int MAX_RUNNING_APPS_PER_USER = 3;

    public FunAiDeployController(DeployClient deployClient,
                                 FunAiAppService funAiAppService,
                                 DeployGitProperties deployGitProperties,
                                 DeployAcrProperties deployAcrProperties) {
        this.deployClient = deployClient;
        this.funAiAppService = funAiAppService;
        this.deployGitProperties = deployGitProperties;
        this.deployAcrProperties = deployAcrProperties;
    }

    @PostMapping("/job/create")
    @Operation(
            summary = "创建部署 Job（通过 API 入口）",
            description = "前端推荐：只传 userId/appId；请求体可不传或传 {}。\n\n" +
                    "后端会自动补齐：repoSshUrl/gitRef/basePath/containerPort/imageTag 等默认值，并调用 deploy 控制面创建 BUILD_AND_DEPLOY Job。"
    )
    public Result<Map<String, Object>> createDeployJob(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) DeployJobCreateRequest req
    ) {
        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            return Result.error("应用不存在或无权限操作");
        }

        // 规则 A：每用户运行中项目数 ≤ 3
        // - 运行中槽位：DEPLOYING + READY（避免“正在部署的第4个”成功后超限）
        // - 若当前 app 已占用槽位（例如 READY 重新部署），则允许继续部署
        Integer st = app.getAppStatus();
        boolean occupiesSlot = st != null && (st == fun.ai.studio.enums.FunAiAppStatus.DEPLOYING.code()
                || st == fun.ai.studio.enums.FunAiAppStatus.READY.code());
        long runningSlots = funAiAppService.countRunningSlotsByUserId(userId);
        if (!occupiesSlot && runningSlots >= MAX_RUNNING_APPS_PER_USER) {
            return Result.error(409, "当前用户最多同时运行/部署 " + MAX_RUNNING_APPS_PER_USER + " 个项目，请先下线一个再部署新项目");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("appId", appId);
        payload.put("appName", app.getAppName());
        if (req != null) {
            if (req.getContainerPort() != null) payload.put("containerPort", req.getContainerPort());
            if (req.getGitRef() != null && !req.getGitRef().isBlank()) payload.put("gitRef", req.getGitRef().trim());
            if (req.getBasePath() != null && !req.getBasePath().isBlank()) payload.put("basePath", req.getBasePath().trim());
            if (req.getImageTag() != null && !req.getImageTag().isBlank()) payload.put("imageTag", req.getImageTag().trim());
            if (req.getImage() != null && !req.getImage().isBlank()) payload.put("image", req.getImage().trim());
        }

        // 方案 C（自建 Git 服务器）：由 API 统一补齐 repoSshUrl/gitRef，避免前端拼接
        try {
            maybeFillGitPayload(payload, userId, appId);
        } catch (Exception ignore) {
        }
        // basePath 默认 /apps/{appId}
        if (!payload.containsKey("basePath") || payload.get("basePath") == null) {
            payload.put("basePath", "/apps/" + appId);
        }
        // containerPort 默认 3000
        if (!payload.containsKey("containerPort") || payload.get("containerPort") == null) {
            payload.put("containerPort", 3000);
        }
        // imageTag 默认 latest（Runner build 时用）
        if (!payload.containsKey("imageTag") || payload.get("imageTag") == null) {
            payload.put("imageTag", "latest");
        }
        // ACR：由 API 统一补齐（Runner 优先使用 payload，避免 101 机器缺环境变量导致失败）
        try {
            maybeFillAcrPayload(payload);
        } catch (Exception ignore) {
        }

        Map<String, Object> body = new HashMap<>();
        body.put("type", "BUILD_AND_DEPLOY");
        body.put("payload", payload);

        Map<String, Object> created = deployClient.createJob(body);
        try {
            // 成功创建 Job 后，将 app 标记为部署中（用于前端展示 & 运行中槽位统计）
            funAiAppService.markDeploying(userId, appId);
        } catch (Exception ignore) {
        }
        return Result.success(created);
    }

    private void maybeFillGitPayload(Map<String, Object> payload, Long userId, Long appId) {
        if (payload == null || userId == null || appId == null) return;
        if (deployGitProperties == null || !deployGitProperties.isEnabled()) return;

        // 1) repoSshUrl
        if (!payload.containsKey("repoSshUrl") || payload.get("repoSshUrl") == null) {
            String sshHost = deployGitProperties.getSshHost();
            int sshPort = deployGitProperties.getSshPort();
            String owner = deployGitProperties.getRepoOwner();
            String repoName = renderRepoName(deployGitProperties.getRepoNameTemplate(), userId, appId);
            if (isBlank(sshHost) || isBlank(owner) || isBlank(repoName)) return;

            // 统一使用 ssh:// 形式，明确端口
            String url = "ssh://git@" + sshHost + ":" + sshPort + "/" + owner + "/" + repoName + ".git";
            payload.put("repoSshUrl", url);
        }

        // 2) gitRef（默认 main）
        if (!payload.containsKey("gitRef") || payload.get("gitRef") == null) {
            String ref = deployGitProperties.getDefaultRef();
            if (!isBlank(ref)) payload.put("gitRef", ref);
        }
    }

    private String renderRepoName(String template, Long userId, Long appId) {
        String t = template == null ? "" : template;
        return t.replace("{userId}", String.valueOf(userId)).replace("{appId}", String.valueOf(appId));
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void maybeFillAcrPayload(Map<String, Object> payload) {
        if (payload == null) return;
        if (deployAcrProperties == null || !deployAcrProperties.isEnabled()) return;
        if (!payload.containsKey("acrRegistry") || payload.get("acrRegistry") == null) {
            String reg = deployAcrProperties.getRegistry();
            if (!isBlank(reg)) payload.put("acrRegistry", reg.trim());
        }
        if (!payload.containsKey("acrNamespace") || payload.get("acrNamespace") == null) {
            String ns = deployAcrProperties.getNamespace();
            if (!isBlank(ns)) payload.put("acrNamespace", ns.trim());
        }
    }

    @GetMapping("/job/info")
    @Operation(summary = "查询 Job（通过 API 入口）")
    public Result<Map<String, Object>> getJob(@RequestParam String jobId) {
        return Result.success(deployClient.getJob(jobId));
    }

    @GetMapping("/job/list")
    @Operation(summary = "列表 Job（通过 API 入口）")
    public Result<List<Map<String, Object>>> list(@RequestParam(defaultValue = "50") int limit) {
        return Result.success(deployClient.listJobs(limit));
    }

    @PostMapping("/job/cancel")
    @Operation(summary = "取消部署 Job（通过 API 入口）", description = "取消指定 jobId（用于解除 appId 部署互斥导致的卡住问题）。")
    public Result<Map<String, Object>> cancel(@RequestParam String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) {
            return Result.error("jobId 不能为空");
        }
        return Result.success(deployClient.cancelJob(jobId.trim()));
    }

    @PostMapping("/app/stop")
    @Operation(summary = "下线已部署应用（通过 API 入口）", description = "API 转发到 Deploy 控制面 /deploy/apps/stop，再由控制面定位 runtime 节点并调用 runtime-agent stop。")
    public Result<Map<String, Object>> stopDeployedApp(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            return Result.error("应用不存在或无权限操作");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("appId", String.valueOf(appId));
        Map<String, Object> resp = deployClient.stopApp(body);

        try {
            funAiAppService.markStopped(userId, appId);
        } catch (Exception ignore) {
        }
        return Result.success(resp);
    }
}


