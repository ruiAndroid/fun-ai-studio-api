package fun.ai.studio.entity.response.deploy.mongo;

import fun.ai.studio.entity.response.WorkspaceMongoCreateCollectionResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeployMongoCreateCollectionResult", description = "统一返回结构（data=WorkspaceMongoCreateCollectionResponse）")
public class DeployMongoCreateCollectionResult {
    @Schema(description = "状态码", example = "200")
    private Integer code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "业务数据")
    private WorkspaceMongoCreateCollectionResponse data;

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

    public WorkspaceMongoCreateCollectionResponse getData() {
        return data;
    }

    public void setData(WorkspaceMongoCreateCollectionResponse data) {
        this.data = data;
    }
}
