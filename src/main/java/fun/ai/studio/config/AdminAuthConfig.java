package fun.ai.studio.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AdminAuthConfig {

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(AdminSecurityProperties props) {
        FilterRegistrationBean<AdminAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AdminAuthFilter(props));
        reg.addUrlPatterns("/api/fun-ai/admin/*");
        // 尽量早执行，避免进入业务层
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }
}


