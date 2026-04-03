package fun.ai.studio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CombinedResetPasswordRequest {

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名")
    private String username;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱地址（需与发送验证码时的邮箱一致）")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "验证码")
    private String code;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度6-20位")
    @Pattern(regexp = "^(?=.*[0-9])(?=.*[a-zA-Z]).{6,20}$", message = "密码必须包含数字和字母")
    @Schema(description = "新密码（6-20位，必须包含数字和字母）")
    private String newPassword;
}
