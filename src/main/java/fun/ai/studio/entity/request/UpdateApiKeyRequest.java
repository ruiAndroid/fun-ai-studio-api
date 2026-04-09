package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "更新API Key请求")
public class UpdateApiKeyRequest {
    @NotBlank(message = "API Key不能为空")
    @Size(max = 128, message = "API Key长度不能超过128字符")
    @Schema(description = "API密钥")
    private String apiKey;
}
