package fun.ai.studio.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 workspace-node 心跳鉴权 filter。
 *
 * 说明：该接口位于 /api/fun-ai/admin/** 下，但不希望复用 X-Admin-Token；
 * 因此需要在 AdminAuthFilter 中对 heartbeat 路径做跳过。
 */
@Configuration
public class WorkspaceNodeRegistryAuthConfig {

    @Bean
    public FilterRegistrationBean<WorkspaceNodeRegistryAuthFilter> workspaceNodeRegistryAuthFilterRegistration(
            WorkspaceNodeRegistryProperties props
    ) {
        FilterRegistrationBean<WorkspaceNodeRegistryAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new WorkspaceNodeRegistryAuthFilter(props));
        reg.addUrlPatterns("/api/fun-ai/admin/workspace-nodes/heartbeat");
        // 需要尽量早执行
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        return reg;
    }
}


