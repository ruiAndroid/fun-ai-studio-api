package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话会话实体类
 */
@Data
@TableName("fun_ai_conversation")
public class FunAiConversation {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "会话ID")
    private Long id;

    /**
     * 用户ID（外键）
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 应用ID（外键）
     */
    @TableField("app_id")
    @Schema(description = "应用ID")
    private Long appId;

    /**
     * 会话标题（可由用户编辑或自动生成）
     */
    @TableField("title")
    @Schema(description = "会话标题")
    private String title;

    /**
     * 消息数量（冗余字段，便于快速查询）
     */
    @TableField("message_count")
    @Schema(description = "消息数量")
    private Integer messageCount;

    /**
     * 最后一条消息时间
     */
    @TableField("last_message_time")
    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastMessageTime;

    /**
     * 是否已归档（归档后不再显示在活跃列表中）
     */
    @TableField("archived")
    @Schema(description = "是否已归档")
    private Boolean archived;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private LocalDateTime updateTime;
}
