package fun.ai.studio.controller.workspace.test;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.response.FunAiWorkspaceApiTestResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.workspace.WorkspaceActivityTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workspace API 测试：在容器内执行 curl 命令
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/test")
@Tag(name = "Fun AI Workspace API 测试", description = "在容器内执行 curl 命令测试接口")
public class FunAiWorkspaceTestController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceTestController.class);

    private final FunAiWorkspaceService workspaceService;
    private final WorkspaceActivityTracker activityTracker;

    public FunAiWorkspaceTestController(FunAiWorkspaceService workspaceService, WorkspaceActivityTracker activityTracker) {
        this.workspaceService = workspaceService;
        this.activityTracker = activityTracker;
    }

    @PostMapping("/api")
    @Operation(summary = "执行 API 测试", description = "在 workspace 容器内执行 curl 命令测试接口")
    public Result<FunAiWorkspaceApiTestResponse> testApi(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "curl 命令", required = true) @RequestParam String curlCommand,
            @Parameter(description = "超时时间（秒）", required = false) @RequestParam(required = false, defaultValue = "30") Integer timeoutSeconds
    ) {
        try {
            activityTracker.touch(userId);
            return Result.success(workspaceService.executeCurlCommand(userId, appId, curlCommand, timeoutSeconds));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("execute curl command failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("execute curl command failed: " + e.getMessage());
        }
    }
}
