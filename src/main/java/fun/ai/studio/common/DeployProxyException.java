package fun.ai.studio.common;

/**
 * API 服务调用 deploy 控制面失败时的语义化异常（用于给前端更可读的提示）。
 */
public class DeployProxyException extends RuntimeException {
    public DeployProxyException(String message) {
        super(message);
    }
}


