package fun.ai.studio.entity.response.deploy.mongo;

import fun.ai.studio.entity.response.WorkspaceMongoDeleteOneResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeployMongoDeleteOneResult", description = "统一返回结构（data=WorkspaceMongoDeleteOneResponse）")
public class DeployMongoDeleteOneResult {
    @Schema(description = "状态码", example = "200")
    private Integer code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "业务数据")
    private WorkspaceMongoDeleteOneResponse data;

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public WorkspaceMongoDeleteOneResponse getData() {
        return data;
    }

    public void setData(WorkspaceMongoDeleteOneResponse data) {
        this.data = data;
    }
}
