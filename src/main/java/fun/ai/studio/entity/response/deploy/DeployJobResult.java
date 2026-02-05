package fun.ai.studio.entity.response.deploy;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeployJobResult", description = "统一返回结构（data=DeployJobResponse）")
public class DeployJobResult {
    @Schema(description = "状态码", example = "200")
    private Integer code;

    @Schema(description = "提示信息")
    private String message;

    @Schema(description = "业务数据")
    private DeployJobResponse data;

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

    public DeployJobResponse getData() {
        return data;
    }

    public void setData(DeployJobResponse data) {
        this.data = data;
    }
}
