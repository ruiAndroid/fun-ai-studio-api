package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 部署链路的 Git（内网）配置：用于 API 在创建 Deploy Job 时补齐 repoSshUrl/gitRef 等字段。
 *
 * <pre>
 * deploy-git.enabled=true
 * deploy-git.ssh-host=172.21.138.103
 * deploy-git.ssh-port=2222
 * deploy-git.repo-owner=funai
 * deploy-git.repo-name-template=u{userId}-app{appId}
 * deploy-git.default-ref=main
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "deploy-git")
public class DeployGitProperties {
    /**
     * 是否启用 Git 信息自动注入（启用后：API 会自动计算 repoSshUrl/gitRef）。
     */
    private boolean enabled = false;

    /**
     * Git SSH Host（内网 IP 或域名）。
     */
    private String sshHost = "";

    /**
     * Git SSH Port（容器化 Gitea 常用 2222；若走宿主机 sshd 则可能是 22）。
     */
    private int sshPort = 2222;

    /**
     * 仓库 owner（Gitea 的用户/组织）。
     */
    private String repoOwner = "funai";

    /**
     * repo 名称模板：支持占位符 {userId}/{appId}
     */
    private String repoNameTemplate = "u{userId}-app{appId}";

    /**
     * 默认 git ref（branch/tag/commit），例如 main。
     */
    private String defaultRef = "main";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSshHost() {
        return sshHost;
    }

    public void setSshHost(String sshHost) {
        this.sshHost = sshHost;
    }

    public int getSshPort() {
        return sshPort;
    }

    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    public String getRepoOwner() {
        return repoOwner;
    }

    public void setRepoOwner(String repoOwner) {
        this.repoOwner = repoOwner;
    }

    public String getRepoNameTemplate() {
        return repoNameTemplate;
    }

    public void setRepoNameTemplate(String repoNameTemplate) {
        this.repoNameTemplate = repoNameTemplate;
    }

    public String getDefaultRef() {
        return defaultRef;
    }

    public void setDefaultRef(String defaultRef) {
        this.defaultRef = defaultRef;
    }
}


