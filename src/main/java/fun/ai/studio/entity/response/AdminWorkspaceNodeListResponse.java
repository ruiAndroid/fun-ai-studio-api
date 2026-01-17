package fun.ai.studio.entity.response;

import lombok.Data;

import java.util.List;

@Data
public class AdminWorkspaceNodeListResponse {
    private Long totalUsers;
    private Long totalPlacements;
    private List<AdminWorkspaceNodeSummary> nodes;
}


