package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Gitea（Git 服务器）自动化配置：用于“创建应用时自动建仓库/授权 runner-bot/team”。
 *
 * <pre>
 * gitea.enabled=true
 * gitea.base-url=http://172.21.138.103:3000
 * gitea.admin-token=...            # Gitea 的管理员 token（建议使用专用管理 token）
 * gitea.owner=funai                # 组织/owner
 * gitea.repo-name-template=u{userId}-app{appId}
 * gitea.auto-init=true             # 是否初始化 main 分支（README）
 * gitea.default-branch=main
 * gitea.runner-team=runner-readonly # 优先：组织 team（Read 权限）
 * gitea.runner-bot=runner-bot      # 兜底：作为协作者加 read 权限
 * gitea.workspace-team=workspace-write # 优先：组织 team（Write 权限）
 * gitea.workspace-bot=workspace-bot # 可选：Workspace 写入 bot（Write 权限，用于前端一键 commit/push）
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "gitea")
public class GiteaProperties {
    private boolean enabled = false;
    private String baseUrl = "";
    private String adminToken = "";

    private String owner = "funai";
    private String repoNameTemplate = "u{userId}-app{appId}";
    private boolean autoInit = true;
    private String defaultBranch = "main";

    /**
     * 优先授权：组织 team name（Read）。
     */
    private String runnerTeam = "runner-readonly";
    /**
     * 兜底授权：bot username（Read）。
     */
    private String runnerBot = "runner-bot";

    /**
     * 优先授权：组织 team name（Write）。
     */
    private String workspaceTeam = "workspace-write";

    /**
     * Workspace 写入 bot（Write）。
     * - 第一阶段前端如需一键 commit/push，可由 Workspace 节点使用该 bot 身份 push
     * - 注意：该 bot 具备写权限，必须与 runner-bot（只读）分离
     */
    private String workspaceBot = "workspace-bot";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getRepoNameTemplate() {
        return repoNameTemplate;
    }

    public void setRepoNameTemplate(String repoNameTemplate) {
        this.repoNameTemplate = repoNameTemplate;
    }

    public boolean isAutoInit() {
        return autoInit;
    }

    public void setAutoInit(boolean autoInit) {
        this.autoInit = autoInit;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getRunnerTeam() {
        return runnerTeam;
    }

    public void setRunnerTeam(String runnerTeam) {
        this.runnerTeam = runnerTeam;
    }

    public String getRunnerBot() {
        return runnerBot;
    }

    public void setRunnerBot(String runnerBot) {
        this.runnerBot = runnerBot;
    }

    public String getWorkspaceTeam() {
        return workspaceTeam;
    }

    public void setWorkspaceTeam(String workspaceTeam) {
        this.workspaceTeam = workspaceTeam;
    }

    public String getWorkspaceBot() {
        return workspaceBot;
    }

    public void setWorkspaceBot(String workspaceBot) {
        this.workspaceBot = workspaceBot;
    }
}


