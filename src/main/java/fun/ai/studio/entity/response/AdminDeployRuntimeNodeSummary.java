package fun.ai.studio.entity.response;

import lombok.Data;

@Data
public class AdminDeployRuntimeNodeSummary {
    private Long nodeId;
    private String name;
    private String agentBaseUrl;
    private String gatewayBaseUrl;
    private Boolean enabled;
    private Integer weight;
    private Long lastHeartbeatAtMs;
    private String health;
}



