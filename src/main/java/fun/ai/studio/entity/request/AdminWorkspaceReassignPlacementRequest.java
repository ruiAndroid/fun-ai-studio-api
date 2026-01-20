package fun.ai.studio.entity.request;

import lombok.Data;

/**
 * 手工迁移：将 userId 的 placement 改到指定 nodeId。
 */
@Data
public class AdminWorkspaceReassignPlacementRequest {
    private Long userId;
    private Long targetNodeId;
}


