package fun.ai.studio.workspace;

import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.service.FunAiWorkspacePlacementService;
import org.springframework.stereotype.Component;

/**
 * 把“按 userId 选择 workspace-node”的逻辑集中到一个地方，供 proxy filter / client / nginx internal 接口复用。
 */
@Component
public class WorkspaceNodeResolver {

    private final FunAiWorkspacePlacementService placementService;

    public WorkspaceNodeResolver(FunAiWorkspacePlacementService placementService) {
        this.placementService = placementService;
    }

    public FunAiWorkspaceNode resolve(Long userId) {
        return placementService.resolveNode(userId);
    }
}


