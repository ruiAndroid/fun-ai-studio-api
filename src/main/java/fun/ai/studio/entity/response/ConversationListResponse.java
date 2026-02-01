package fun.ai.studio.entity.response;

import fun.ai.studio.entity.FunAiConversation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "会话列表响应")
public class ConversationListResponse {
    
    @Schema(description = "会话列表")
    private List<FunAiConversation> conversations;
    
    @Schema(description = "总数")
    private Integer total;
    
    @Schema(description = "当前应用允许的最大会话数")
    private Integer maxConversations;
}
