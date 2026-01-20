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
    /**
     * 最近一次心跳时间（epoch ms）。为空表示从未上报。
     */
    private Long lastHeartbeatAtMs;
    /**
     * 健康状态：HEALTHY / STALE / UNKNOWN
     */
    private String health;
}


