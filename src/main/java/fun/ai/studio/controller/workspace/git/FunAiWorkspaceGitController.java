package fun.ai.studio.controller.workspace.git;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.request.WorkspaceGitCommitPushRequest;
import fun.ai.studio.entity.response.WorkspaceGitEnsureResponse;
import fun.ai.studio.entity.response.WorkspaceGitStatusResponse;
import fun.ai.studio.entity.response.WorkspaceGitLogResponse;
import fun.ai.studio.entity.response.WorkspaceGitCommitPushResponse;
import fun.ai.studio.entity.response.WorkspaceGitRevertResponse;
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

    @GetMapping("/log")
    @Operation(
            summary = "查看提交历史",
            description = "返回最近 N 次提交记录（默认 10 条，最多 50 条），包含 SHA、作者、时间、提交信息"
    )
    public Result<WorkspaceGitLogResponse> log(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "返回条数（默认 10，最多 50）") @RequestParam(defaultValue = "10") Integer limit
    ) {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            return Result.error("workspace-node 未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitLogResponse resp = workspaceNodeClient.gitLog(userId, appId, limit == null ? 10 : limit);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("git log failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git log failed: " + e.getMessage());
        }
    }

    @PostMapping("/commit-push")
    @Operation(
            summary = "一键提交并推送",
            description = "将所有改动 add + commit + push 到远端（使用 workspace-bot 身份提交）。\n\n" +
                    "返回 result 字段：\n" +
                    "- SUCCESS：commit + push 成功\n" +
                    "- NO_CHANGES：没有需要提交的改动\n" +
                    "- PUSH_FAILED：commit 成功但 push 失败（可能有冲突，需先 pull）\n" +
                    "- FAILED：操作失败"
    )
    public Result<WorkspaceGitCommitPushResponse> commitPush(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) WorkspaceGitCommitPushRequest request
    ) {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            return Result.error("workspace-node 未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        try {
            WorkspaceGitCommitPushResponse resp = workspaceNodeClient.gitCommitPush(userId, appId, request);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("git commit-push failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return Result.error("git commit-push failed: " + e.getMessage());
        }
    }

    @PostMapping("/revert")
    @Operation(
            summary = "回退到某次提交",
            description = "使用 git revert 生成一个新的 commit 来撤销指定提交的改动（不改写历史），并自动 push。\n\n" +
                    "注意：这不是 reset，而是生成一个新的 revert commit。\n\n" +
                    "返回 result 字段：\n" +
                    "- SUCCESS：revert + push 成功\n" +
                    "- CONFLICT：revert 时有冲突\n" +
                    "- PUSH_FAILED：revert 成功但 push 失败\n" +
                    "- FAILED：操作失败"
    )
    public Result<WorkspaceGitRevertResponse> revert(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "要撤销的 commit SHA（完整或短 SHA）", required = true) @RequestParam String commitSha
    ) {
        if (workspaceNodeClient == null || !workspaceNodeClient.isEnabled()) {
            return Result.error("workspace-node 未启用");
        }
        if (userId == null || appId == null) {
            return Result.error("userId/appId 不能为空");
        }
        if (commitSha == null || commitSha.isBlank()) {
            return Result.error("commitSha 不能为空");
        }
        try {
            WorkspaceGitRevertResponse resp = workspaceNodeClient.gitRevert(userId, appId, commitSha);
            return Result.success(resp);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("git revert failed: userId={}, appId={}, commitSha={}, error={}", userId, appId, commitSha, e.getMessage(), e);
            return Result.error("git revert failed: " + e.getMessage());
        }
    }
}

