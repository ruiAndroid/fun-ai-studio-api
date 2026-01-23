package fun.ai.studio.entity.request;

/**
 * Workspace Git commit-push 请求
 */
public class WorkspaceGitCommitPushRequest {
    /**
     * 提交信息（可选，不填则使用默认）
     */
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

