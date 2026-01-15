package fun.ai.studio.common;

/**
 * 小机裁剪模式下：workspace 请求应被代理到大机 workspace-node。
 * 当代理链路不可用/未生效时抛出，用于给前端更可读的错误提示。
 */
public class WorkspaceNodeProxyException extends RuntimeException {
    public WorkspaceNodeProxyException(String message) {
        super(message);
    }
}


