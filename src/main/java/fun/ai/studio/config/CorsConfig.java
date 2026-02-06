package fun.ai.studio.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        // 允许来源（支持 origin pattern；逗号分隔）
        // 说明：因为 allowCredentials=true，所以不要使用 allowedOrigins="*"
        // 这里默认放行：本地开发 + 现网前端域名
        String patterns = allowedOriginPatterns;
        if (patterns == null || patterns.isBlank()) {
            patterns = "http://localhost:5173,http://127.0.0.1:5173,https://funar.funshion.com,https://*.funshion.com";
        }
        Arrays.stream(patterns.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(config::addAllowedOriginPattern);
        // 允许任何头
        config.addAllowedHeader("*");
        // 允许任何方法（POST、GET等）
        config.addAllowedMethod("*");
        // 允许携带凭证信息（如Cookie）
        config.setAllowCredentials(true);
        // 预检请求的有效期，单位为秒
        config.setMaxAge(3600L);
        
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Value("${funai.cors.allowed-origin-patterns:}")
    private String allowedOriginPatterns;
}