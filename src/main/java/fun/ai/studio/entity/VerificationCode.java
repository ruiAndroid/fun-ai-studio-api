package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 验证码实体
 */
@Data
@TableName("fun_ai_verification_code")
public class VerificationCode {

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 邮箱地址
     */
    @TableField("email")
    private String email;

    /**
     * 验证码（6位数字）
     */
    @TableField("code")
    private String code;

    /**
     * 类型：1=忘记密码，2=绑定邮箱
     */
    @TableField("type")
    private Integer type;

    /**
     * 过期时间
     */
    @TableField("expired_time")
    private LocalDateTime expiredTime;

    /**
     * 是否已使用：0=未使用，1=已使用
     */
    @TableField("used")
    private Boolean used;

    /**
     * 连续错误次数
     */
    @TableField("error_count")
    private Integer errorCount;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createTime;

    /**
     * 验证码类型常量
     */
    public static final int TYPE_PASSWORD_RESET = 1;
    public static final int TYPE_BIND_EMAIL = 2;
    public static final int TYPE_REGISTER = 3;
}
