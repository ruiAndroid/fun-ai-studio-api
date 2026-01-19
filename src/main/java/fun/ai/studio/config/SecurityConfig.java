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
import org.springframework.security.web.access.AccessDeniedHandler;
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
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )
                .authorizeHttpRequests(auth -> auth
                        // 确保 Swagger UI 相关路径都被允许（放在最前面，优先级最高）
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/swagger-ui/index.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        // 管理接口：不走 JWT，由 AdminAuthFilter 做 IP + Token 鉴权
                        .requestMatchers("/api/fun-ai/admin/**").permitAll()
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
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json;charset=UTF-8");
            Result<Object> result = Result.error(403, "Access Denied");
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
        // Workspace Mongo Explorer（静态页面；API 仍需鉴权）
        "/workspace-mongo.html",
        // Workspace/Deploy 节点管理（静态页面；API 由 AdminAuthFilter 鉴权）
        "/workspace-nodes.html",
                        "/nodes.html",
        "/nodes-admin.html",
        "/admin/nodes.html",
        "/admin/nodes-admin.html",
                        "/workspace-nodes-admin.html",
        "/deploy-nodes.html",
        // /doc 页面 mermaid 渲染脚本（必须放行，否则会被 401 JSON 拦截导致浏览器拒绝执行）
        "/doc-mermaid.js",
        "/chatui/**",
        "/api/fun-ai/auth/**",
        // 在线文档
        "/doc/**",
        // 根路径 *.md 仅用于 302 跳转到 /doc/**（避免根路径被鉴权拦截）
        "/*.md",
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