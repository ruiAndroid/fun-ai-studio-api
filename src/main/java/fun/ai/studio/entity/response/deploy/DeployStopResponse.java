package fun.ai.studio.entity.response.deploy;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(name = "DeployStopResponse", description = "下线部署应用响应")
public class DeployStopResponse {
    @Schema(description = "应用ID")
    private String appId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "运行态节点ID")
    private Long nodeId;

    @Schema(description = "runtime-agent 基址")
    private String agentBaseUrl;

    @Schema(description = "runtime-agent 返回内容")
    private Map<String, Object> runtime;

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public Map<String, Object> getRuntime() {
        return runtime;
    }

    public void setRuntime(Map<String, Object> runtime) {
        this.runtime = runtime;
    }
}
