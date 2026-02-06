package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请码（一次性消耗）
 */
@Data
@TableName("fun_ai_invite_code")
public class FunAiInviteCode {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @TableField("code")
    @Schema(description = "邀请码（唯一）", example = "FA8K3QZ1P2")
    private String code;

    @TableField("status")
    @Schema(description = "状态（0=未使用；1=已使用）")
    private Integer status;

    @TableField("used_by_user_id")
    @Schema(description = "使用者 userId（仅 status=1 时有值）")
    private Long usedByUserId;

    @TableField("used_at")
    @Schema(description = "使用时间（仅 status=1 时有值）")
    private LocalDateTime usedAt;

    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

