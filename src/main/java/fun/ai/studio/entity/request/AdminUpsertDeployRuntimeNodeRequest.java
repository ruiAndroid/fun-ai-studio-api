package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class AdminUpsertDeployRuntimeNodeRequest {
    private String name;
    private String agentBaseUrl;
    private String gatewayBaseUrl;
    private Boolean enabled;
    private Integer weight;
}



