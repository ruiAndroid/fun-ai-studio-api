package fun.ai.studio.gitea;

import fun.ai.studio.config.GiteaProperties;
import fun.ai.studio.config.DeployAcrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 在“创建应用（app）”时自动创建 Gitea 仓库并授权：
 * - runner（只读）：runner-team 或 runner-bot
 * - workspace（写入，可选）：workspace-bot
 *
 * 设计：best-effort，不阻塞主流程；失败后可后续重试（可通过运维脚本/接口补齐）。
 */
@Service
@ConditionalOnProperty(prefix = "gitea", name = "enabled", havingValue = "true")
public class GiteaRepoAutomationService {
    private static final Logger log = LoggerFactory.getLogger(GiteaRepoAutomationService.class);

    private final GiteaProperties props;
    private final GiteaClient client;
    private final DeployAcrProperties acrProps;

    public GiteaRepoAutomationService(GiteaProperties props, GiteaClient client, DeployAcrProperties acrProps) {
        this.props = props;
        this.client = client;
        this.acrProps = acrProps;
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

        // Workspace 写入：优先 team（Write），兜底 workspace-bot（Write）
        boolean workspaceGranted = false;
        if (StringUtils.hasText(props.getWorkspaceTeam())) {
            try {
                Long teamId = client.findTeamIdByName(owner, props.getWorkspaceTeam().trim());
                if (teamId != null && teamId > 0) {
                    workspaceGranted = client.grantTeamRepo(teamId, owner, repo);
                }
            } catch (Exception ignore) {
            }
        }

        if (!workspaceGranted && StringUtils.hasText(props.getWorkspaceBot())) {
            try {
                workspaceGranted = client.addCollaboratorWrite(owner, repo, props.getWorkspaceBot().trim());
            } catch (Exception ignore) {
            }
        }

        if (!workspaceGranted) {
            log.warn("gitea grant workspace write failed: owner={}, repo={}, workspaceTeam={}, workspaceBot={}",
                    owner, repo, props.getWorkspaceTeam(), props.getWorkspaceBot());
        } else {
            log.info("gitea repo ready: {}/{} (workspace write granted)", owner, repo);
        }

        // 初始化模板文件（避免 Runner build 因缺 Dockerfile 失败）
        try {
            String branch = props.getDefaultBranch();
            String gitignore = """
                    # Logs
                    logs
                    *.log
                    npm-debug.log*
                    yarn-debug.log*
                    yarn-error.log*
                    pnpm-debug.log*
                    lerna-debug.log*
                    
                    node_modules
                    dist
                    dist-ssr
                    *.local
                    .npm-cache/
                    # Editor directories and files
                    .vscode/*
                    !.vscode/extensions.json
                    .idea
                    .DS_Store
                    *.suo
                    *.ntvs*
                    *.njsproj
                    *.sln
                    *.sw?
                    
                    # Database
                    server/checkin.db
                    """;
            // 关键：Runner(101) 可能无法访问 DockerHub，因此 Dockerfile 的基础镜像建议使用 ACR 镜像
            // 约定：将 node:20-alpine 推送到 ACR：<acrRegistry>/<acrNamespace>/base-node:20-alpine
            String baseImage = "base-node:latest";
            try {
                if (acrProps != null && acrProps.isEnabled()
                        && StringUtils.hasText(acrProps.getRegistry())
                        && StringUtils.hasText(acrProps.getNamespace())) {
                    baseImage = acrProps.getRegistry().trim() + "/" + acrProps.getNamespace().trim() + "/base-node:latest";
                }
            } catch (Exception ignore) {
            }
            String dockerfile = """
                    FROM %s
                    
                    WORKDIR /app
                    
                    # 接收构建参数（Runner 会自动传递）
                    ARG NPM_REGISTRY=https://registry.npmjs.org
                    
                    # 复制 .npmrc（如果存在，优先使用）
                    COPY .npmrc* ./
                    
                    # 如果没有 .npmrc，使用构建参数配置 registry
                    RUN if [ ! -f .npmrc ]; then echo "registry=${NPM_REGISTRY}" > .npmrc; fi
                    
                    # 复制依赖文件
                    COPY package*.json ./
                    
                    # 安装依赖（优先使用 npm ci，如果没有 lockfile 则使用 npm install）
                    RUN npm ci 2>/dev/null || npm install
                    
                    # 复制源代码
                    COPY . .
                    
                    # 构建（如果有 build 脚本）
                    RUN npm run build 2>/dev/null || echo "No build script, skipping..."
                    
                    ENV PORT=3000
                    ENV NODE_ENV=production
                    EXPOSE 3000
                    
                    CMD ["npm","run","start"]
                    """.formatted(baseImage);
            String dockerignore = """
                    node_modules
                    dist
                    .npm-cache/
                    .git
                    .idea
                    *.log
                    """;
            client.ensureFile(owner, repo, branch, ".gitignore", gitignore, "init .gitignore");
            client.ensureFile(owner, repo, branch, "Dockerfile", dockerfile, "init Dockerfile");
            client.ensureFile(owner, repo, branch, ".dockerignore", dockerignore, "init .dockerignore");
        } catch (Exception e) {
            log.warn("gitea ensure template files failed: owner={}, repo={}, err={}", owner, repo, e.getMessage());
        }
    }

    /**
     * 在“删除应用”时 best-effort 删除对应 Gitea 仓库（失败不阻塞主流程）。
     */
    public boolean deleteRepoOnAppDeleted(Long userId, Long appId) {
        if (userId == null || appId == null) return false;
        if (props == null || client == null || !client.isEnabled()) return false;

        String owner = props.getOwner();
        String repo = renderRepoName(props.getRepoNameTemplate(), userId, appId);
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) return false;

        boolean ok = client.deleteRepo(owner, repo);
        if (!ok) {
            log.warn("gitea delete repo failed: owner={}, repo={}, userId={}, appId={}", owner, repo, userId, appId);
        } else {
            log.info("gitea repo deleted: {}/{} (userId={}, appId={})", owner, repo, userId, appId);
        }
        return ok;
    }

    private String renderRepoName(String template, Long userId, Long appId) {
        String t = template == null ? "" : template;
        return t.replace("{userId}", String.valueOf(userId)).replace("{appId}", String.valueOf(appId));
    }
}


