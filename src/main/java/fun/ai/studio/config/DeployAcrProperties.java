package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 部署链路的 ACR（镜像仓库）配置：用于 API 在创建 Deploy Job 时补齐 acrRegistry/acrNamespace，
 * 从而保证“前端只传 userId/appId”也能 build+push。
 *
 * <pre>
 * deploy-acr.enabled=true
 * deploy-acr.registry=crpi-xxx.cn-hangzhou.personal.cr.aliyuncs.com
 * deploy-acr.namespace=funaistudio
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "deploy-acr")
public class DeployAcrProperties {
    private boolean enabled = false;
    private String registry = "";
    private String namespace = "funaistudio";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRegistry() {
        return registry;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}


