package fun.ai.studio.config;

import fun.ai.studio.entity.response.FunAiWorkspaceApiTestResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileReadResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceInfoResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceStatusResponse;
import fun.ai.studio.service.FunAiWorkspaceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * 本地开发模式 stub：当 workspace-node-proxy.enabled=false 且 funai.workspace.enabled=false 时，
 * 提供一个 FunAiWorkspaceService 空实现，所有操作抛 UnsupportedOperationException。
 */
@Configuration
@Conditional(WorkspaceLocalCondition.class)
public class WorkspaceLocalStubConfig {

    private static final UnsupportedOperationException NOT_SUPPORTED =
            new UnsupportedOperationException(
                    "Workspace 功能在本地开发模式下已禁用。"
                            + "如需启用：请在 application.properties 中设置 workspace-node-proxy.enabled=true "
                            + "并确保可以访问远程 workspace-node 服务；"
                            + "或者设置 funai.workspace.enabled=true（需要本地 Docker 环境）。");

    @Bean
    @ConditionalOnMissingBean(FunAiWorkspaceService.class)
    public FunAiWorkspaceService funAiWorkspaceServiceLocalStub() {
        return new FunAiWorkspaceService() {

            @Override
            public FunAiWorkspaceInfoResponse ensureWorkspace(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceStatusResponse getStatus(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceProjectDirResponse ensureAppDir(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceProjectDirResponse uploadAppZip(Long userId, Long appId, MultipartFile file, boolean overwrite) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse startDev(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse startBuild(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse startPreview(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse startInstall(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse stopRun(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceRunStatusResponse getRunStatus(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public void clearRunLog(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public Path exportAppAsZip(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceFileTreeResponse listFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceFileReadResponse readFileContent(Long userId, Long appId, String path) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceFileReadResponse writeFileContent(Long userId, Long appId, String path, String content, boolean createParents, Long expectedLastModifiedMs, boolean forceWrite) { throw NOT_SUPPORTED; }

            @Override
            public void createDirectory(Long userId, Long appId, String path, boolean createParents) { throw NOT_SUPPORTED; }

            @Override
            public void deletePath(Long userId, Long appId, String path) { throw NOT_SUPPORTED; }

            @Override
            public void movePath(Long userId, Long appId, String fromPath, String toPath, boolean overwrite) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceFileReadResponse uploadFile(Long userId, Long appId, String path, MultipartFile file, boolean overwrite, boolean createParents) { throw NOT_SUPPORTED; }

            @Override
            public Path downloadFile(Long userId, Long appId, String path) { throw NOT_SUPPORTED; }

            @Override
            public boolean stopRunForIdle(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public void stopContainerForIdle(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceStatusResponse removeWorkspaceContainer(Long userId) { throw NOT_SUPPORTED; }

            @Override
            public void cleanupWorkspaceOnAppDeleted(Long userId, Long appId) { throw NOT_SUPPORTED; }

            @Override
            public FunAiWorkspaceApiTestResponse executeCurlCommand(Long userId, Long appId, String command, Integer timeoutSeconds) { throw NOT_SUPPORTED; }
        };
    }
}
