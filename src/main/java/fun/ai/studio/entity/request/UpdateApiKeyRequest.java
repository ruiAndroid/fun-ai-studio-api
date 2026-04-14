package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "更新API Key请求")
public class UpdateApiKeyRequest {
@Schema(description = "API密钥")
    private String apiKey;
}
