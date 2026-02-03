package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import fun.ai.studio.entity.FunAiConversation;
import fun.ai.studio.entity.FunAiConversationMessage;
import fun.ai.studio.entity.response.ConversationDetailResponse;
import fun.ai.studio.entity.response.ConversationListResponse;
import fun.ai.studio.mapper.FunAiConversationMapper;
import fun.ai.studio.mapper.FunAiConversationMessageMapper;
import fun.ai.studio.service.FunAiConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FunAiConversationServiceImpl implements FunAiConversationService {
    
    private final FunAiConversationMapper conversationMapper;
    private final FunAiConversationMessageMapper messageMapper;
    
    @Value("${funai.conversation.max-conversations-per-app:5}")
    private int maxConversationsPerApp;
    
    @Value("${funai.conversation.max-messages-per-conversation:30}")
    private int maxMessagesPerConversation;
    
    public FunAiConversationServiceImpl(FunAiConversationMapper conversationMapper,
                                       FunAiConversationMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }
    
    @Override
    @Transactional
    public FunAiConversation createConversation(Long userId, Long appId, String title) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        
        // 检查会话数量限制（不再区分归档状态，所有会话都计入）
        long count = conversationMapper.selectCount(
            new LambdaQueryWrapper<FunAiConversation>()
                .eq(FunAiConversation::getUserId, userId)
                .eq(FunAiConversation::getAppId, appId)
        );
        
        if (count >= maxConversationsPerApp) {
            throw new IllegalArgumentException(
                "该应用的会话数已达上限（" + maxConversationsPerApp + "），请删除旧会话后再创建"
            );
        }
        
        FunAiConversation conversation = new FunAiConversation();
        conversation.setUserId(userId);
        conversation.setAppId(appId);
        conversation.setTitle(title != null && !title.isBlank() ? title : "新会话");
        conversation.setMessageCount(0);
        conversation.setLastMessageTime(LocalDateTime.now());
        conversation.setArchived(false);
        
        conversationMapper.insert(conversation);
        return conversation;
    }
    
    @Override
    public ConversationListResponse listConversations(Long userId, Long appId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        
        // 查询所有会话，按最后消息时间倒序
        List<FunAiConversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<FunAiConversation>()
                .eq(FunAiConversation::getUserId, userId)
                .eq(FunAiConversation::getAppId, appId)
                .orderByDesc(FunAiConversation::getLastMessageTime)
        );
        
        ConversationListResponse response = new ConversationListResponse();
        response.setConversations(conversations);
        response.setTotal(conversations.size());
        response.setMaxConversations(maxConversationsPerApp);
        
        return response;
    }
    
    @Override
    public ConversationDetailResponse getConversationDetail(Long userId, Long conversationId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        
        FunAiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        
        List<FunAiConversationMessage> messages = messageMapper.selectList(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
                .orderByAsc(FunAiConversationMessage::getSequence)
        );
        
        ConversationDetailResponse response = new ConversationDetailResponse();
        response.setConversation(conversation);
        response.setMessages(messages);
        response.setMaxMessages(maxMessagesPerConversation);
        
        return response;
    }
    
    @Override
    @Transactional
    public FunAiConversationMessage addMessage(Long userId, Long conversationId, String role, String content, String gitCommitSha) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        
        // 验证会话归属
        FunAiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        
        // 检查消息数量限制
        long messageCount = messageMapper.selectCount(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
        );
        
        if (messageCount >= maxMessagesPerConversation) {
            throw new IllegalArgumentException(
                "该会话的消息数已达上限（" + maxMessagesPerConversation + "），请创建新会话"
            );
        }
        
        // 获取下一个序号
        Integer maxSequence = messageMapper.selectList(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
                .orderByDesc(FunAiConversationMessage::getSequence)
                .last("LIMIT 1")
        ).stream()
            .map(FunAiConversationMessage::getSequence)
            .findFirst()
            .orElse(0);
        
        FunAiConversationMessage message = new FunAiConversationMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setSequence(maxSequence + 1);
        message.setGitCommitSha(gitCommitSha);  // 设置 Git commit SHA
        
        messageMapper.insert(message);
        
        // 更新会话的消息数量和最后消息时间
        conversationMapper.update(null,
            new LambdaUpdateWrapper<FunAiConversation>()
                .eq(FunAiConversation::getId, conversationId)
                .set(FunAiConversation::getMessageCount, messageCount + 1)
                .set(FunAiConversation::getLastMessageTime, LocalDateTime.now())
        );
        
        return message;
    }
    
    @Override
    @Transactional
    public void updateConversationTitle(Long userId, Long conversationId, String title) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        
        FunAiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        
        conversationMapper.update(null,
            new LambdaUpdateWrapper<FunAiConversation>()
                .eq(FunAiConversation::getId, conversationId)
                .set(FunAiConversation::getTitle, title)
        );
    }
    
    @Override
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        
        FunAiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        
        // 删除所有消息
        messageMapper.delete(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
        );
        
        // 删除会话
        conversationMapper.deleteById(conversationId);
    }
    
    @Override
    @Transactional
    public void rollbackToMessage(Long userId, Long conversationId, Long messageId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        
        // 验证会话归属
        FunAiConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        
        // 验证消息存在且属于该会话
        FunAiConversationMessage targetMessage = messageMapper.selectById(messageId);
        if (targetMessage == null) {
            throw new IllegalArgumentException("消息不存在");
        }
        if (!targetMessage.getConversationId().equals(conversationId)) {
            throw new IllegalArgumentException("消息不属于该会话");
        }
        
        // 删除该消息之后的所有消息（sequence > targetMessage.sequence）
        messageMapper.delete(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
                .gt(FunAiConversationMessage::getSequence, targetMessage.getSequence())
        );
        
        // 重新计算消息数量
        long newMessageCount = messageMapper.selectCount(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
        );
        
        // 获取最后一条消息的时间
        LocalDateTime lastMessageTime = messageMapper.selectList(
            new LambdaQueryWrapper<FunAiConversationMessage>()
                .eq(FunAiConversationMessage::getConversationId, conversationId)
                .orderByDesc(FunAiConversationMessage::getSequence)
                .last("LIMIT 1")
        ).stream()
            .map(FunAiConversationMessage::getCreateTime)
            .findFirst()
            .orElse(LocalDateTime.now());
        
        // 更新会话的消息数量和最后消息时间
        conversationMapper.update(null,
            new LambdaUpdateWrapper<FunAiConversation>()
                .eq(FunAiConversation::getId, conversationId)
                .set(FunAiConversation::getMessageCount, newMessageCount)
                .set(FunAiConversation::getLastMessageTime, lastMessageTime)
        );
    }
    
    @Override
    @Transactional
    public void deleteConversationsByApp(Long userId, Long appId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (appId == null) {
            throw new IllegalArgumentException("appId 不能为空");
        }
        
        // 查询该应用的所有会话
        List<FunAiConversation> conversations = conversationMapper.selectList(
            new LambdaQueryWrapper<FunAiConversation>()
                .eq(FunAiConversation::getUserId, userId)
                .eq(FunAiConversation::getAppId, appId)
        );

        // 批量删除消息（避免 N+1：每个会话一次 delete）
        if (conversations != null && !conversations.isEmpty()) {
            List<Long> ids = conversations.stream()
                    .filter(c -> c != null && c.getId() != null)
                    .map(FunAiConversation::getId)
                    .toList();
            // MySQL 对 IN 参数有上限，做简单分片更稳（应用内会话数量通常不大）
            final int batchSize = 500;
            for (int i = 0; i < ids.size(); i += batchSize) {
                List<Long> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
                if (batch.isEmpty()) continue;
                messageMapper.delete(
                        new LambdaQueryWrapper<FunAiConversationMessage>()
                                .in(FunAiConversationMessage::getConversationId, batch)
                );
            }
        }
        
        // 删除所有会话
        conversationMapper.delete(
            new LambdaQueryWrapper<FunAiConversation>()
                .eq(FunAiConversation::getUserId, userId)
                .eq(FunAiConversation::getAppId, appId)
        );
    }
}
