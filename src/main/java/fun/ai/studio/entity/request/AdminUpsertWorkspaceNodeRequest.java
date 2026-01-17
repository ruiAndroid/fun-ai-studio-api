package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class AdminUpsertWorkspaceNodeRequest {
    private Long id;
    private String name;
    private String nginxBaseUrl;
    private String apiBaseUrl;
    private Integer enabled;
    private Integer weight;
}


