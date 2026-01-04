package fun.ai.studio.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workspace（用户在线开发容器）配置
 */
@Component
@ConfigurationProperties(prefix = "funai.workspace")
public class WorkspaceProperties {

    /**
     * 是否启用 workspace 功能
     */
    private boolean enabled = false;

    /**
     * workspace 宿主机持久化根目录：{hostRoot}/{userId}/apps/{appId}
     */
    private String hostRoot;

    /**
     * workspace 容器镜像（建议使用你自己的 ACR 镜像）
     */
    private String image;

    /**
     * 容器内 dev server 端口（Vite 默认 5173）
     */
    private int containerPort = 5173;

    /**
     * 宿主机端口分配起始值
     */
    private int hostPortBase = 20000;

    /**
     * 单机最多尝试分配的端口数量（从 hostPortBase 开始向后扫描）
     */
    private int hostPortScanSize = 2000;

    /**
     * 容器内 workspace 挂载目录
     */
    private String containerWorkdir = "/workspace";

    /**
     * 容器名前缀：ws-u-{userId}
     */
    private String containerNamePrefix = "ws-u-";

    private String httpProxy;
    private String httpsProxy;
    private String noProxy;

    /**
     * 无操作多少分钟后自动 stop run（默认 10 分钟）
     */
    private int idleStopRunMinutes = 10;

    /**
     * 无操作多少分钟后自动 stop 容器（默认 20 分钟）
     */
    private int idleStopContainerMinutes = 20;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHostRoot() {
        return hostRoot;
    }

    public void setHostRoot(String hostRoot) {
        this.hostRoot = hostRoot;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public int getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(int containerPort) {
        this.containerPort = containerPort;
    }

    public int getHostPortBase() {
        return hostPortBase;
    }

    public void setHostPortBase(int hostPortBase) {
        this.hostPortBase = hostPortBase;
    }

    public int getHostPortScanSize() {
        return hostPortScanSize;
    }

    public void setHostPortScanSize(int hostPortScanSize) {
        this.hostPortScanSize = hostPortScanSize;
    }

    public String getContainerWorkdir() {
        return containerWorkdir;
    }

    public void setContainerWorkdir(String containerWorkdir) {
        this.containerWorkdir = containerWorkdir;
    }

    public String getContainerNamePrefix() {
        return containerNamePrefix;
    }

    public void setContainerNamePrefix(String containerNamePrefix) {
        this.containerNamePrefix = containerNamePrefix;
    }

    public String getHttpProxy() {
        return httpProxy;
    }

    public void setHttpProxy(String httpProxy) {
        this.httpProxy = httpProxy;
    }

    public String getHttpsProxy() {
        return httpsProxy;
    }

    public void setHttpsProxy(String httpsProxy) {
        this.httpsProxy = httpsProxy;
    }

    public String getNoProxy() {
        return noProxy;
    }

    public void setNoProxy(String noProxy) {
        this.noProxy = noProxy;
    }

    public int getIdleStopRunMinutes() {
        return idleStopRunMinutes;
    }

    public void setIdleStopRunMinutes(int idleStopRunMinutes) {
        this.idleStopRunMinutes = idleStopRunMinutes;
    }

    public int getIdleStopContainerMinutes() {
        return idleStopContainerMinutes;
    }

    public void setIdleStopContainerMinutes(int idleStopContainerMinutes) {
        this.idleStopContainerMinutes = idleStopContainerMinutes;
    }
}


