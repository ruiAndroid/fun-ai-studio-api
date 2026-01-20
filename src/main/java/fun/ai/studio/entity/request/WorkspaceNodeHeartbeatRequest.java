package fun.ai.studio.entity.request;

import jakarta.validation.constraints.NotBlank;

/**
 * workspace-node -> API 心跳上报请求。
 */
public class WorkspaceNodeHeartbeatRequest {
    @NotBlank(message = "nodeName 不能为空")
    private String nodeName;

    @NotBlank(message = "nginxBaseUrl 不能为空")
    private String nginxBaseUrl;

    @NotBlank(message = "apiBaseUrl 不能为空")
    private String apiBaseUrl;

    /**
     * 可选：节点版本/构建号等。
     */
    private String version;

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getNginxBaseUrl() {
        return nginxBaseUrl;
    }

    public void setNginxBaseUrl(String nginxBaseUrl) {
        this.nginxBaseUrl = nginxBaseUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}


