package fun.ai.studio.service;

import fun.ai.studio.entity.FunAiConversation;
import fun.ai.studio.entity.FunAiConversationMessage;
import fun.ai.studio.entity.response.ConversationDetailResponse;
import fun.ai.studio.entity.response.ConversationListResponse;

import java.util.List;

public interface FunAiConversationService {
    
    /**
     * 创建新会话
     */
    FunAiConversation createConversation(Long userId, Long appId, String title);
    
    /**
     * 获取应用的会话列表
     */
    ConversationListResponse listConversations(Long userId, Long appId);
    
    /**
     * 获取会话详情（包含消息列表）
     */
    ConversationDetailResponse getConversationDetail(Long userId, Long conversationId);
    
    /**
     * 添加消息到会话
     */
    FunAiConversationMessage addMessage(Long userId, Long conversationId, String role, String content, String gitCommitSha);
    
    /**
     * 更新会话标题
     */
    void updateConversationTitle(Long userId, Long conversationId, String title);
    
    /**
     * 删除会话（及其所有消息）
     */
    void deleteConversation(Long userId, Long conversationId);
    
    /**
     * 回退到指定消息节点（删除该消息之后的所有消息）
     */
    void rollbackToMessage(Long userId, Long conversationId, Long messageId);
    
    /**
     * 删除应用时清理所有会话
     */
    void deleteConversationsByApp(Long userId, Long appId);
}
