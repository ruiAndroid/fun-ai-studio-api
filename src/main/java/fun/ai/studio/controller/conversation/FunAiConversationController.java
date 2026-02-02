package fun.ai.studio.controller.conversation;

import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiConversation;
import fun.ai.studio.entity.FunAiConversationMessage;
import fun.ai.studio.entity.request.ConversationMessageAddRequest;
import fun.ai.studio.entity.response.ConversationDetailResponse;
import fun.ai.studio.entity.response.ConversationListResponse;
import fun.ai.studio.service.FunAiConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * AI 对话会话管理
 */
@RestController
@RequestMapping("/api/fun-ai/conversation")
@Tag(name = "Fun AI 对话管理", description = "AI 智能体对话上下文管理")
public class FunAiConversationController {
    private static final Logger log = LoggerFactory.getLogger(FunAiConversationController.class);
    
    private final FunAiConversationService conversationService;
    
    public FunAiConversationController(FunAiConversationService conversationService) {
        this.conversationService = conversationService;
    }
    
    @PostMapping("/create")
    @Operation(summary = "创建新会话", description = "为指定应用创建新的对话会话")
    public Result<FunAiConversation> createConversation(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "会话标题（可选）") @RequestParam(required = false) String title) {
        try {
            FunAiConversation conversation = conversationService.createConversation(userId, appId, title);
            return Result.success(conversation);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("create conversation failed: error={}", e.getMessage(), e);
            return Result.error("创建会话失败: " + e.getMessage());
        }
    }
    
    @GetMapping("/list")
    @Operation(summary = "获取会话列表", description = "获取指定应用的所有会话")
    public Result<ConversationListResponse> listConversations(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId) {
        try {
            ConversationListResponse response = conversationService.listConversations(userId, appId);
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("list conversations failed: appId={}, error={}", appId, e.getMessage(), e);
            return Result.error("获取会话列表失败: " + e.getMessage());
        }
    }
    
    @GetMapping("/detail")
    @Operation(summary = "获取会话详情", description = "获取会话详情及其所有消息")
    public Result<ConversationDetailResponse> getConversationDetail(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "会话ID", required = true) @RequestParam Long conversationId) {
        try {
            ConversationDetailResponse response = conversationService.getConversationDetail(userId, conversationId);
            return Result.success(response);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("get conversation detail failed: conversationId={}, error={}", conversationId, e.getMessage(), e);
            return Result.error("获取会话详情失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/message/add")
    @Operation(summary = "添加消息", description = "向会话中添加新消息")
    public Result<FunAiConversationMessage> addMessage(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Valid @RequestBody ConversationMessageAddRequest request) {
        try {
            FunAiConversationMessage message = conversationService.addMessage(
                userId, request.getConversationId(), request.getRole(), request.getContent(), request.getGitCommitSha()
            );
            return Result.success(message);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("add message failed: conversationId={}, error={}", 
                request.getConversationId(), e.getMessage(), e);
            return Result.error("添加消息失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/title")
    @Operation(summary = "更新会话标题", description = "修改会话的标题")
    public Result<Void> updateTitle(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "会话ID", required = true) @RequestParam Long conversationId,
            @Parameter(description = "新标题", required = true) @RequestParam String title) {
        try {
            conversationService.updateConversationTitle(userId, conversationId, title);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("update conversation title failed: conversationId={}, error={}", 
                conversationId, e.getMessage(), e);
            return Result.error("更新标题失败: " + e.getMessage());
        }
    }
    
    @GetMapping("/delete")
    @Operation(summary = "删除会话", description = "删除会话及其所有消息")
    public Result<Void> deleteConversation(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "会话ID", required = true) @RequestParam Long conversationId) {
        try {
            conversationService.deleteConversation(userId, conversationId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("delete conversation failed: conversationId={}, error={}", 
                conversationId, e.getMessage(), e);
            return Result.error("删除会话失败: " + e.getMessage());
        }
    }
    
    @PostMapping("/rollback")
    @Operation(summary = "回退到指定消息", description = "删除指定消息之后的所有消息，保留该消息及之前的消息")
    public Result<Void> rollbackToMessage(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "会话ID", required = true) @RequestParam Long conversationId,
            @Parameter(description = "消息ID（回退到此消息，删除之后的消息）", required = true) @RequestParam Long messageId) {
        try {
            conversationService.rollbackToMessage(userId, conversationId, messageId);
            return Result.success(null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("rollback to message failed: conversationId={}, messageId={}, error={}", 
                conversationId, messageId, e.getMessage(), e);
            return Result.error("回退消息失败: " + e.getMessage());
        }
    }
}
