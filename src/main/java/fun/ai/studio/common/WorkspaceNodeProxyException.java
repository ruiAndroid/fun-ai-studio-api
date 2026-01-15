package fun.ai.studio.common;

/**
 * API 服务器（小机）裁剪模式下：workspace 请求应被代理到 Workspace 开发服务器（大机）workspace-node。
 * 当代理链路不可用/未生效时抛出，用于给前端更可读的错误提示。
 */
public class WorkspaceNodeProxyException extends RuntimeException {
    public WorkspaceNodeProxyException(String message) {
        super(message);
    }
}


