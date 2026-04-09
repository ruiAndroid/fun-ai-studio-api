package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("fun_ai_feedback")
@Schema(description = "用户反馈实体")
public class Feedback {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "反馈ID")
    private Long id;

    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    @TableField("app_id")
    @Schema(description = "应用ID")
    private Long appId;

    @TableField("title")
    @Schema(description = "反馈标题")
    private String title;

    @TableField("content")
    @Schema(description = "反馈内容")
    private String content;

    @TableField("images")
    @Schema(description = "图片URL列表（JSON数组格式）")
    private String images;

    @TableField("status")
    @Schema(description = "处理状态：0-待处理，1-处理中，2-已处理")
    private Integer status;

    @TableField("reply")
    @Schema(description = "管理员回复内容")
    private String reply;

    @TableField("reply_time")
    @Schema(description = "管理员回复时间")
    private LocalDateTime replyTime;

    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private LocalDateTime updateTime;

    // ----------- 非数据库字段 -----------

    @TableField(exist = false)
    @Schema(description = "反馈用户信息")
    private FunAiUser user;

    @TableField(exist = false)
    @Schema(description = "应用信息")
    private FunAiApp app;

    @TableField(exist = false)
    @Schema(description = "图片URL列表（非数据库字段）")
    private List<String> imageList;
}
