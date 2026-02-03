package fun.ai.studio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.security.JwtAuthenticationFilter;
import fun.ai.studio.utils.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import jakarta.servlet.DispatcherType;
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
                        // 避免 async dispatch（SSE/Streaming）二次进入 Security 导致 response 已提交仍抛 AccessDeniedException 刷屏
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
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
        // Spring Boot 默认错误页（避免 error dispatch 再次被鉴权拦截导致日志刷屏）
        "/error",
        "/error/**",
        "/swagger-ui.html",//接口文档（兼容旧路径）
        "/swagger-ui/**",//Swagger UI 新路径
        "/swagger-ui/index.html",//Swagger UI 首页
        "/favicon.ico", // 浏览器默认请求站点图标
        // Mongo 数据库管理（静态页面；API 仍需鉴权）
        "/mongo.html",
        "/workspace-mongo.html",
        "/deploy-mongo.html",
        // Workspace/Deploy 节点管理（静态页面；API 由 AdminAuthFilter 鉴权）
        "/workspace-nodes.html",
        "/nodes.html",
        "/nodes-admin.html",
        "/admin/nodes.html",
        "/admin/nodes-admin.html",
        "/workspace-nodes-admin.html",
        "/deploy-nodes.html",
        // 预览入口（/preview/{appId}/...）：公开访问（通常由 Nginx 反代到 workspace-dev / hostPort；若请求落到 Spring 也不应被 JWT 拦截）
        "/preview/**",
        // 运行态入口（/runtime/{appId}/...）：公开访问（通常由网关/反代指向 runtime 节点；若请求落到 Spring 也不应被 JWT 拦截）
        "/runtime/**",
        // Vite 开发预览常见根路径资源（若 nginx 未正确路由到 /preview/{appId}，也不应被 JWT 拦截；落到 API 时返回 404 更合理）
        "/@vite/**",
        "/@react-refresh",
        "/src/**",
        "/assets/**",
        // Vite 默认模板静态资源（很多新建项目会引用 <img src="/vite.svg">，会打到站点根路径）
        "/vite.svg",
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
        "/v3/api-docs/**",
        "/webjars/**",
        // "/fun-ai-app/**" 旧静态站点入口已移除（全量 workspace 在线运行）


    };
}