package fun.ai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * API 服务器（小机）-> Workspace 开发服务器（大机）workspace-node 反向代理配置。
 *
 * <p>注意：Spring Boot 3 要求配置 key 使用 kebab-case（全小写）。</p>
 *
 * <p>示例：</p>
 * <pre>
 * workspace-node-proxy.enabled=true
 * workspace-node-proxy.base-url=http://39.97.61.139:7001
 * workspace-node-proxy.shared-secret=xxxx
 * workspace-node-proxy.connect-timeout-ms=2000
 * workspace-node-proxy.read-timeout-ms=0
 * workspace-node-proxy.body-spool-threshold-bytes=2097152
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "workspace-node-proxy")
public class WorkspaceNodeProxyProperties {
    /**
     * 是否启用 API 服务器（小机）应用层代理（代理 /api/fun-ai/workspace/** 到 Workspace 开发服务器（大机）workspace-node）。
     */
    private boolean enabled = false;

    /**
     * Workspace 开发服务器（大机）workspace-node 基地址，例如 http://39.97.61.139:7001
     */
    private String baseUrl = "http://127.0.0.1:7001";

    /**
     * 与 Workspace 开发服务器（大机）workspace-node.internal.shared-secret 一致。
     */
    private String sharedSecret = "";

    /**
     * 连接超时
     */
    private long connectTimeoutMs = 2000;

    /**
     * 请求总超时（包含读取）。0 表示不设置（对 SSE/下载更友好）。
     */
    private long readTimeoutMs = 0;

    /**
     * run/status 专用超时（毫秒）：避免 workspace-node 正在清理/重启时导致 API list/info 被长时间阻塞。
     * - 0：不设置（不推荐）
     * - 建议：800~2000
     */
    private long runStatusTimeoutMs = 1200;

    /**
     * 为了生成签名需要计算 body sha256：
     * - 小于阈值：直接读入内存
     * - 大于阈值：落盘到临时文件（避免 OOM）
     */
    private long bodySpoolThresholdBytes = 2 * 1024 * 1024;

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

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getRunStatusTimeoutMs() {
        return runStatusTimeoutMs;
    }

    public void setRunStatusTimeoutMs(long runStatusTimeoutMs) {
        this.runStatusTimeoutMs = runStatusTimeoutMs;
    }

    public long getBodySpoolThresholdBytes() {
        return bodySpoolThresholdBytes;
    }

    public void setBodySpoolThresholdBytes(long bodySpoolThresholdBytes) {
        this.bodySpoolThresholdBytes = bodySpoolThresholdBytes;
    }

    public long connectTimeoutNanos() {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(0, connectTimeoutMs));
    }
}


