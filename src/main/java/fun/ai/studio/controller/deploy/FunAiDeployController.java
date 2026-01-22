package fun.ai.studio.controller.deploy;

import fun.ai.studio.common.Result;
import fun.ai.studio.config.DeployGitProperties;
import fun.ai.studio.deploy.DeployClient;
import fun.ai.studio.entity.FunAiApp;
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
    private static final int MAX_RUNNING_APPS_PER_USER = 3;

    public FunAiDeployController(DeployClient deployClient,
                                 FunAiAppService funAiAppService,
                                 DeployGitProperties deployGitProperties) {
        this.deployClient = deployClient;
        this.funAiAppService = funAiAppService;
        this.deployGitProperties = deployGitProperties;
    }

    @PostMapping("/job/create")
    @Operation(summary = "创建部署 Job（通过 API 入口）", description = "校验应用归属后，调用 deploy 控制面创建 BUILD_AND_DEPLOY Job。")
    public Result<Map<String, Object>> createDeployJob(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) Map<String, Object> extraPayload
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
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.putAll(extraPayload);
        }

        // 方案 C（自建 Git 服务器）：由 API 统一补齐 repoSshUrl/gitRef，避免前端拼接
        try {
            maybeFillGitPayload(payload, userId, appId);
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
}


