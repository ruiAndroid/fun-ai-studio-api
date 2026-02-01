package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotNull;

@Data
@Schema(description = "创建会话请求")
public class ConversationCreateRequest {
    
    @NotNull(message = "appId 不能为空")
    @Schema(description = "应用ID", required = true)
    private Long appId;
    
    @Schema(description = "会话标题（可选，不提供则自动生成）")
    private String title;
}
