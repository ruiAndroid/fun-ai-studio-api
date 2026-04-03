package fun.ai.studio.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 当 workspace-node-proxy.enabled=false 且 funai.workspace.enabled=false 时激活。
 * 用于本地开发模式的降级 stub 配置。
 */
public class WorkspaceLocalCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String proxyEnabled = context.getEnvironment().getProperty("workspace-node-proxy.enabled", "false");
        String workspaceEnabled = context.getEnvironment().getProperty("funai.workspace.enabled", "false");
        return !Boolean.parseBoolean(proxyEnabled) && !Boolean.parseBoolean(workspaceEnabled);
    }
}
