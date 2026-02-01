package fun.ai.studio.entity.response;

import fun.ai.studio.entity.FunAiConversation;
import fun.ai.studio.entity.FunAiConversationMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "会话详情响应")
public class ConversationDetailResponse {
    
    @Schema(description = "会话信息")
    private FunAiConversation conversation;
    
    @Schema(description = "消息列表")
    private List<FunAiConversationMessage> messages;
    
    @Schema(description = "当前会话允许的最大消息数")
    private Integer maxMessages;
}
