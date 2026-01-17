package fun.ai.studio.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 API 服务器（小机）-> Workspace 开发服务器（大机）workspace-node 的应用层代理 filter。
 *
 * <p>注意：必须让 Spring Security 先执行鉴权，再进入本 filter 转发。
 * Spring Security 的过滤器通常 order 更小（优先执行）；这里设置为较大的 order。</p>
 */
@Configuration
public class WorkspaceNodeProxyConfig {

    @Bean
    public FilterRegistrationBean<WorkspaceNodeProxyFilter> workspaceNodeProxyFilterRegistration(
            WorkspaceNodeProxyProperties props,
            fun.ai.studio.workspace.WorkspaceNodeResolver resolver
    ) {
        FilterRegistrationBean<WorkspaceNodeProxyFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new WorkspaceNodeProxyFilter(props, resolver));
        reg.addUrlPatterns("/api/fun-ai/workspace/*");
        // 确保在 Spring Security 之后执行（Security filter chain 通常 order 更小）
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return reg;
    }
}


