package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * workspace-node 节点注册/心跳配置（API 服务侧）。
 *
 * <pre>
 * funai.workspace-node-registry.enabled=true
 * funai.workspace-node-registry.shared-secret=4f2b1a9c8d3e7a60b1c9d7e5f3a8b6c4d2e0f9a7c5b3d1e8f6a4c2e9b7d5f0a1c3e8b2d6f9a0c4e7b1d5f8a2c6e9b3d7f0a4c8e1b5d9f2a6c0e3b7d1f4a8c2e5b9d0f3a7c1e4b8d2f5a9c3e6b0d4f7a1c5e8b2d6f9a0c4e7b1d5f8a2c6e9b3d7f0a4c8e1b5d9f2a6c0e3b7d1f4a8c2
 * funai.workspace-node-registry.allowed-ips=172.21.138.87
 * funai.workspace-node-registry.heartbeat-stale-seconds=60
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "funai.workspace-node-registry")
public class WorkspaceNodeRegistryProperties {
    private boolean enabled = true;
    private String sharedSecret = "";
    private List<String> allowedIps = new ArrayList<>();
    private long heartbeatStaleSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    public long getHeartbeatStaleSeconds() {
        return heartbeatStaleSeconds;
    }

    public void setHeartbeatStaleSeconds(long heartbeatStaleSeconds) {
        this.heartbeatStaleSeconds = heartbeatStaleSeconds;
    }

    public Duration heartbeatStaleDuration() {
        long s = Math.max(1, heartbeatStaleSeconds);
        return Duration.ofSeconds(s);
    }
}


