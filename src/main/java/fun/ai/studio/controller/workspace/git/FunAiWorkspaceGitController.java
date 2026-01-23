package fun.ai.studio.controller.workspace.git;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.response.WorkspaceGitEnsureResponse;
import fun.ai.studio.entity.response.WorkspaceGitStatusResponse;
import fun.ai.studio.workspace.WorkspaceNodeClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * Workspace Git 接口（API 服务器侧，转发到 workspace-node）
 *
 * <p>前端调用 91 的 /api/fun-ai/workspace/git/*，API 通过 WorkspaceNodeClient 转发到 87。</p>
 */
@RestController
@RequestMapping("/api/fun-ai/workspace/git")
@Tag(name = "Workspace Git", description = "Workspace Git 状态与同步接口（clone/pull/status）")
public class FunAiWorkspaceGitController {
    private static final Logger log = LoggerFactory.getLogger(FunAiWorkspaceGitController.class);

    @Autowired(required = false)
    private WorkspaceNodeClient workspaceNodeClient;

    @GetMapping("/status")
    @Operation(
            summary = "获取 Git 状态",
            description = "返回指定 app 目录的 Git 状态：是否 git repo、是否 dirty（有未提交改动）、当前分支、commit SHA、远端 URL"
    )
    public Result<WorkspaceGitStatusResponse> status(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            return Result.error("workspace-node 未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitStatusResponse resp = workspaceNodeClient.gitStatus(userId, appId);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("git status failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git status failed: " + e.getMessage());
        }
    }

    @PostMapping("/ensure")
    @Operation(
            summary = "确保 Git 同步",
            description = "确保 app 目录与远端仓库同步：\n" +
                    "- 目录不存在/空：clone\n" +
                    "- 目录存在且是 git repo + clean：pull\n" +
                    "- 目录存在且是 git repo + dirty：返回 NEED_COMMIT（需要用户先 commit）\n" +
                    "- 目录存在但非 git repo/非空：返回 NEED_CONFIRM（需要用户手动处理）\n\n" +
                    "返回 result 字段：CLONED / PULLED / ALREADY_UP_TO_DATE / NEED_COMMIT / NEED_CONFIRM / FAILED"
    )
    public Result<WorkspaceGitEnsureResponse> ensure(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            return Result.error("workspace-node 未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitEnsureResponse resp = workspaceNodeClient.gitEnsure(userId, appId);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("git ensure failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git ensure failed: " + e.getMessage());
        }
    }
}

