package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class AdminDeployReassignPlacementRequest {
    private Long appId;
    private Long targetNodeId;
}



