package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * API 服务 -> Deploy 控制面服务调用配置（双机/多机模式）。
 *
 * <pre>
 * deploy-proxy.enabled=true
 * deploy-proxy.base-url=http://127.0.0.1:7002
 * deploy-proxy.shared-secret=xxxx   # API -> Deploy 内部鉴权密钥（Deploy 侧校验 Header：X-DEPLOY-SECRET）
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "deploy-proxy")
public class DeployProxyProperties {
    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:7002";
    private String sharedSecret = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }
}


