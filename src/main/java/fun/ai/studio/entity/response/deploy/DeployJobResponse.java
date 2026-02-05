package fun.ai.studio.entity.response.deploy;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(name = "DeployJobResponse", description = "部署 Job 响应")
public class DeployJobResponse {
    @Schema(description = "Job ID")
    private String id;

    @Schema(description = "Job 类型")
    private String type;

    @Schema(description = "Job 状态")
    private String status;

    @Schema(description = "Job payload")
    private Map<String, Object> payload;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "Runner ID")
    private String runnerId;

    @Schema(description = "租约过期时间（ISO-8601）")
    private Instant leaseExpireAt;

    @Schema(description = "运行态节点")
    private DeployRuntimeNode runtimeNode;

    @Schema(description = "部署后的访问地址")
    private String deployUrl;

    @Schema(description = "创建时间（ISO-8601）")
    private Instant createdAt;

    @Schema(description = "更新时间（ISO-8601）")
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getRunnerId() {
        return runnerId;
    }

    public void setRunnerId(String runnerId) {
        this.runnerId = runnerId;
    }

    public Instant getLeaseExpireAt() {
        return leaseExpireAt;
    }

    public void setLeaseExpireAt(Instant leaseExpireAt) {
        this.leaseExpireAt = leaseExpireAt;
    }

    public DeployRuntimeNode getRuntimeNode() {
        return runtimeNode;
    }

    public void setRuntimeNode(DeployRuntimeNode runtimeNode) {
        this.runtimeNode = runtimeNode;
    }

    public String getDeployUrl() {
        return deployUrl;
    }

    public void setDeployUrl(String deployUrl) {
        this.deployUrl = deployUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
