package fun.ai.studio.entity.response.deploy;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "DeployRuntimeNode", description = "运行态节点信息")
public class DeployRuntimeNode {
    @Schema(description = "节点ID")
    private Long id;

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "runtime-agent 基址")
    private String agentBaseUrl;

    @Schema(description = "gateway 基址")
    private String gatewayBaseUrl;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "权重")
    private Integer weight;

    @Schema(description = "最后心跳时间（ISO-8601）")
    private Instant lastHeartbeatAt;

    @Schema(description = "磁盘可用百分比")
    private Double diskFreePct;

    @Schema(description = "磁盘可用字节数")
    private Long diskFreeBytes;

    @Schema(description = "容器数量")
    private Integer containerCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAgentBaseUrl() {
        return agentBaseUrl;
    }

    public void setAgentBaseUrl(String agentBaseUrl) {
        this.agentBaseUrl = agentBaseUrl;
    }

    public String getGatewayBaseUrl() {
        return gatewayBaseUrl;
    }

    public void setGatewayBaseUrl(String gatewayBaseUrl) {
        this.gatewayBaseUrl = gatewayBaseUrl;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Double getDiskFreePct() {
        return diskFreePct;
    }

    public void setDiskFreePct(Double diskFreePct) {
        this.diskFreePct = diskFreePct;
    }

    public Long getDiskFreeBytes() {
        return diskFreeBytes;
    }

    public void setDiskFreeBytes(Long diskFreeBytes) {
        this.diskFreeBytes = diskFreeBytes;
    }

    public Integer getContainerCount() {
        return containerCount;
    }

    public void setContainerCount(Integer containerCount) {
        this.containerCount = containerCount;
    }
}
