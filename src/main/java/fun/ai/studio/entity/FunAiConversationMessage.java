package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话消息实体类
 */
@Data
@TableName("fun_ai_conversation_message")
public class FunAiConversationMessage {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "消息ID")
    private Long id;

    /**
     * 会话ID（外键）
     */
    @TableField("conversation_id")
    @Schema(description = "会话ID")
    private Long conversationId;

    /**
     * 消息角色：user/assistant/system
     */
    @TableField("role")
    @Schema(description = "消息角色", allowableValues = {"user", "assistant", "system"})
    private String role;

    /**
     * 消息内容
     */
    @TableField("content")
    @Schema(description = "消息内容")
    private String content;

    /**
     * 消息序号（在会话中的顺序，从1开始）
     */
    @TableField("sequence")
    @Schema(description = "消息序号")
    private Integer sequence;

    /**
     * Git commit SHA（可选，关联代码提交）
     */
    @TableField("git_commit_sha")
    @Schema(description = "Git commit SHA")
    private String gitCommitSha;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createTime;
}
