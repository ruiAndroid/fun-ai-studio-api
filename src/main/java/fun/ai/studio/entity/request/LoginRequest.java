package fun.ai.studio.entity.request;

import lombok.Data;

@Data
public class LoginRequest {
    private String userName;
    private String password;
}