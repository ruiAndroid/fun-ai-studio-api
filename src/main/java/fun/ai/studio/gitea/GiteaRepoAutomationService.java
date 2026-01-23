package fun.ai.studio.gitea;

import fun.ai.studio.config.GiteaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 在“创建应用（app）”时自动创建 Gitea 仓库并授权 runner 只读。
 *
 * 设计：best-effort，不阻塞主流程；失败后可后续重试（可通过运维脚本/接口补齐）。
 */
@Service
@ConditionalOnProperty(prefix = "gitea", name = "enabled", havingValue = "true")
public class GiteaRepoAutomationService {
    private static final Logger log = LoggerFactory.getLogger(GiteaRepoAutomationService.class);

    private final GiteaProperties props;
    private final GiteaClient client;

    public GiteaRepoAutomationService(GiteaProperties props, GiteaClient client) {
        this.props = props;
        this.client = client;
    }

    public void ensureRepoAndGrantRunner(Long userId, Long appId) {
        if (userId == null || appId == null) return;
        if (props == null || client == null || !client.isEnabled()) return;

        String owner = props.getOwner();
        String repo = renderRepoName(props.getRepoNameTemplate(), userId, appId);
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) return;

        boolean ok = client.ensureOrgRepo(owner, repo, true, props.isAutoInit(), props.getDefaultBranch());
        if (!ok) {
            log.warn("gitea ensure repo failed: owner={}, repo={}, userId={}, appId={}", owner, repo, userId, appId);
            return;
        }

        // 优先：team 授权（Read）
        boolean granted = false;
        if (StringUtils.hasText(props.getRunnerTeam())) {
            try {
                Long teamId = client.findTeamIdByName(owner, props.getRunnerTeam().trim());
                if (teamId != null && teamId > 0) {
                    granted = client.grantTeamRepo(teamId, owner, repo);
                }
            } catch (Exception ignore) {
            }
        }

        // 兜底：给 runner-bot 加 read 协作者
        if (!granted && StringUtils.hasText(props.getRunnerBot())) {
            granted = client.addCollaboratorReadOnly(owner, repo, props.getRunnerBot().trim());
        }

        if (!granted) {
            log.warn("gitea grant runner read failed: owner={}, repo={}, runnerTeam={}, runnerBot={}",
                    owner, repo, props.getRunnerTeam(), props.getRunnerBot());
        } else {
            log.info("gitea repo ready: {}/{} (runner access granted)", owner, repo);
        }
    }

    private String renderRepoName(String template, Long userId, Long appId) {
        String t = template == null ? "" : template;
        return t.replace("{userId}", String.valueOf(userId)).replace("{appId}", String.valueOf(appId));
    }
}


