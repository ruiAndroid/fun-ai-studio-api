package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理接口鉴权（不依赖用户JWT）：IP 白名单 + Header Token。
 *
 * <p>示例：</p>
 * <pre>
 * funai.admin.enabled=true
 * funai.admin.allowed-ips=172.17.5.80,127.0.0.1,172.17.0.0/16
 * funai.admin.token=CHANGE_ME_STRONG_ADMIN_TOKEN
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "funai.admin")
public class AdminSecurityProperties {
    private boolean enabled = true;
    private List<String> allowedIps = new ArrayList<>();
    private String token = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}


