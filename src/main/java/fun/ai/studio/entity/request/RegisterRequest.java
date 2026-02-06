package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String userName;
    private String password;
    private String phone;
    private String avatar;
    private String email;
    /**
     * 邀请码（当 funai.auth.invite.enabled=true 时必填）
     */
    private String inviteCode;
}