package fun.ai.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.security.JwtAuthenticationFilter;
import fun.ai.studio.utils.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;

    public SecurityConfig(@Lazy UserDetailsService userDetailsService,  JwtUtil jwtUtil) {
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        // 确保 Swagger UI 相关路径都被允许（放在最前面，优先级最高）
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/swagger-ui/index.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        .requestMatchers(URL_WHITELIST).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            
            Result<Object> result = Result.error(401, "请先登录");
            ObjectMapper objectMapper = new ObjectMapper();
            response.getWriter().write(objectMapper.writeValueAsString(result));
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(userDetailsService, jwtUtil);
    }
    
    //白名单接口，不需要鉴权可以直接调用的
    public static final String[] URL_WHITELIST = {
        "/swagger-ui.html",//接口文档（兼容旧路径）
        "/swagger-ui/**",//Swagger UI 新路径
        "/swagger-ui/index.html",//Swagger UI 首页
        "/favicon.ico", // 浏览器默认请求站点图标
        "/chatui/**",
        "/api/fun-ai/auth/**",
        // 在线文档
        "/doc/**",
        // nginx auth_request 内部端口查询（仅用于同机 nginx 反代；接口内部还会校验来源IP）
        "/api/fun-ai/workspace/internal/**",
        // 在线编辑器实时通道：SSE / WebSocket（应用归属在业务层校验，前端也无法在 WS 握手中自定义 Header）
        "/api/fun-ai/workspace/realtime/**",
        "/api/fun-ai/workspace/ws/**",
        "/v3/api-docs/**",
        "/webjars/**",
        // "/fun-ai-app/**" 旧静态站点入口已移除（全量 workspace 在线运行）


    };
}