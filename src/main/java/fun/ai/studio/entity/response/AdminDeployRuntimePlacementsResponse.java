package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class AdminDeployRuntimePlacementsResponse {
    private Long nodeId;
    private Long total;
    private List<AdminDeployRuntimePlacementItem> items;
}



