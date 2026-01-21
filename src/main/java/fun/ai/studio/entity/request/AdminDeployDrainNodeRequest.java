package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class AdminDeployDrainNodeRequest {
    private Long sourceNodeId;
    private Long targetNodeId;
    private Integer limit;
}



