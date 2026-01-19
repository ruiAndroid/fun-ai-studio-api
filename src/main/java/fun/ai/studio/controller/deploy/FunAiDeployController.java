package fun.ai.studio.controller.deploy;

import fun.ai.studio.common.Result;
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

    public FunAiDeployController(DeployClient deployClient, FunAiAppService funAiAppService) {
        this.deployClient = deployClient;
        this.funAiAppService = funAiAppService;
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

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("appId", appId);
        payload.put("appName", app.getAppName());
        if (extraPayload != null && !extraPayload.isEmpty()) {
            payload.putAll(extraPayload);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("type", "BUILD_AND_DEPLOY");
        body.put("payload", payload);

        return Result.success(deployClient.createJob(body));
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


