package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Data
@Schema(description = "添加消息请求")
public class ConversationMessageAddRequest {
    
    @NotNull(message = "conversationId 不能为空")
    @Schema(description = "会话ID", required = true)
    private Long conversationId;
    
    @NotBlank(message = "role 不能为空")
    @Pattern(regexp = "^(user|assistant|system)$", message = "role 必须是 user、assistant 或 system")
    @Schema(description = "消息角色", required = true, allowableValues = {"user", "assistant", "system"})
    private String role;
    
    @NotBlank(message = "content 不能为空")
    @Schema(description = "消息内容", required = true)
    private String content;
}
