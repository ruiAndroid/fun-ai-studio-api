package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class AdminWorkspaceNodeSummary {
    private Long nodeId;
    private String name;
    private String nginxBaseUrl;
    private String apiBaseUrl;
    private Integer enabled;
    private Integer weight;
    private Long assignedUsers;
}


