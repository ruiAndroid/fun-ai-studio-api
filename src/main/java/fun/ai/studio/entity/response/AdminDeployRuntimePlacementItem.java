package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class AdminDeployRuntimePlacementItem {
    private Long appId;
    private Long nodeId;
    private String lastActiveAt;
}



