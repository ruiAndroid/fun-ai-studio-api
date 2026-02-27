package fun.ai.studio.config;

import fun.ai.studio.entity.response.FunAiWorkspaceApiTestResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import fun.ai.studio.common.WorkspaceNodeProxyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * API 服务器（小机）裁剪：当 workspace-node-proxy.enabled=true 时，不启用本机的容器/文件系统重实现，
 * 但仍保留 controller 以展示 Swagger 文档，因此提供一个“占位”FunAiWorkspaceService 供依赖注入。
 *
 * <p>运行时请求会被 WorkspaceNodeProxyFilter 代理到 Workspace 开发服务器（大机）；正常情况下不会走到这个 stub。</p>
 */
@Configuration
@ConditionalOnProperty(name = "workspace-node-proxy.enabled", havingValue = "true")
public class WorkspaceStubServiceConfig {

    @Bean
    @ConditionalOnMissingBean(FunAiWorkspaceService.class)
    public FunAiWorkspaceService funAiWorkspaceServiceStub() {
        return new FunAiWorkspaceService() {
            private RuntimeException disabled() {
                return new WorkspaceNodeProxyException(
                        "workspace 已迁移到 Workspace 开发服务器（大机）容器节点（workspace-node）。" +
                        "请检查：1) API 服务器（小机）配置 workspace-node-proxy.enabled=true；" +
                        "2) API 服务器（小机）到 Workspace 开发服务器（大机）7001 的网络/安全组；" +
                        "3) shared-secret 是否一致；" +
                        "4) Workspace 开发服务器（大机）workspace-node 服务是否在线。"
                );
            }

            @Override
            public FunAiWorkspaceInfoResponse ensureWorkspace(Long userId) { throw disabled(); }

            @Override
            public FunAiWorkspaceStatusResponse getStatus(Long userId) { throw disabled(); }

            @Override
            public FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse startBuild(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse startPreview(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse startInstall(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse stopRun(Long userId) { throw disabled(); }

            @Override
            public FunAiWorkspaceRunStatusResponse getRunStatus(Long userId) { throw disabled(); }

            @Override
            public void clearRunLog(Long userId, Long appId) { throw disabled(); }

            @Override
            public Path exportAppAsZip(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceFileTreeResponse listFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries) { throw disabled(); }

            @Override
            public FunAiWorkspaceFileReadResponse readFileContent(Long userId, Long appId, String path) { throw disabled(); }

            @Override
            public FunAiWorkspaceFileReadResponse writeFileContent(Long userId, Long appId, String path, String content, boolean createParents, Long expectedLastModifiedMs, boolean forceWrite) { throw disabled(); }

            @Override
            public void createDirectory(Long userId, Long appId, String path, boolean createParents) { throw disabled(); }

            @Override
            public void deletePath(Long userId, Long appId, String path) { throw disabled(); }

            @Override
            public void movePath(Long userId, Long appId, String fromPath, String toPath, boolean overwrite) { throw disabled(); }

            @Override
            public FunAiWorkspaceFileReadResponse uploadFile(Long userId, Long appId, String path, MultipartFile file, boolean overwrite, boolean createParents) { throw disabled(); }

            @Override
            public Path downloadFile(Long userId, Long appId, String path) { throw disabled(); }

            @Override
            public boolean stopRunForIdle(Long userId) { throw disabled(); }

            @Override
            public void stopContainerForIdle(Long userId) { throw disabled(); }

            @Override
            public FunAiWorkspaceStatusResponse removeWorkspaceContainer(Long userId) { throw disabled(); }

            @Override
            public void cleanupWorkspaceOnAppDeleted(Long userId, Long appId) { throw disabled(); }

            @Override
            public FunAiWorkspaceApiTestResponse executeCurlCommand(Long userId, Long appId, String command, Integer timeoutSeconds) { throw disabled(); }
        };
    }
}


