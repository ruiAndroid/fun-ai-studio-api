package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class RegisterRequest {
    private String userName;
    private String password;
    private String phone;
    private String avatar;
    private String email;
}