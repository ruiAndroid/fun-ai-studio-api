package fun.ai.studio.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.utils.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;


@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final UserDetailsService userDetailsService;

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(UserDetailsService userDetailsService, JwtUtil jwtUtil) {
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
    }

    private String getOrCreateRequestId(HttpServletRequest request) {
        Object existing = request.getAttribute("REQ_ID");
        if (existing instanceof String s && !s.isBlank()) {
            return s;
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        request.setAttribute("REQ_ID", id);
        return id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = getOrCreateRequestId(request);
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        logger.info("[{}] {} method={} uri={}{} thread={}",
                requestId,
                this.getClass().getSimpleName(),
                method,
                uri,
                (query == null ? "" : ("?" + query)),
                Thread.currentThread().getName());
        String url = request.getServletPath();

        // 跳过 Swagger UI 相关路径
        if (url.startsWith("/swagger-ui") || url.startsWith("/v3/api-docs") || url.startsWith("/webjars")) {
            chain.doFilter(request, response);
            return;
        }
        
        final String requestTokenHeader = request.getHeader("Authorization");

        String username = null;
        String jwtToken = null;

        // JWT Token的格式为 "Bearer token"
        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
            jwtToken = requestTokenHeader.substring(7);
            try {
                username = jwtUtil.getUsernameFromToken(jwtToken);
            } catch (IllegalArgumentException e) {
                logger.warn("Unable to get JWT Token: {}", e.getMessage());
                sendErrorResponse(response, "请求中没有JWT Token");
                return;
            } catch (ExpiredJwtException e) {
                logger.warn("JWT Token已过期: {}", e.getMessage());
                sendErrorResponse(response, "JWT Token已过期");
                return;
            } catch (SignatureException e) {
                logger.warn("JWT签名不匹配: {}", e.getMessage());
                sendErrorResponse(response, "JWT签名无效");
                return;
            } catch (MalformedJwtException e) {
                logger.warn("JWT格式错误: {}", e.getMessage());
                sendErrorResponse(response, "JWT格式无效");
                return;
            } catch (UnsupportedJwtException e) {
                logger.warn("不支持的JWT Token: {}", e.getMessage());
                sendErrorResponse(response, "不支持的JWT Token");
                return;
            } catch (Exception e) {
                logger.warn("JWT Token处理异常: {}", e.getMessage());
                sendErrorResponse(response, "JWT Token无效");
                return;
            }

            // 验证token
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);
                    if (jwtUtil.validateToken(jwtToken, userDetails.getUsername())) {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    } else {
                        logger.warn("JWT Token验证失败");
                        sendErrorResponse(response, "JWT Token无效");
                        return;
                    }
                } catch (Exception e) {
                    // MySQL 挂掉/连接拒绝时，这里会抛异常（selectOne 无法执行）。
                    // 不应误报为 401（无权限），而应返回 503 提示“认证后端不可用”。
                    if (isLikelyDbDown(e)) {
                        logger.warn("用户认证失败（疑似数据库不可用）: {}", e.getMessage());
                        sendErrorResponse(response, HttpStatus.SERVICE_UNAVAILABLE, 503, "认证服务不可用（数据库连接失败），请稍后重试");
                    } else {
                        logger.warn("用户认证失败: {}", e.getMessage());
                        sendErrorResponse(response, HttpStatus.UNAUTHORIZED, 401, "用户认证失败");
                    }
                    return;
                }
            }
        }
        // 如果没有Authorization头或者不是Bearer Token格式，则直接放行
        // 让Spring Security的其他配置来决定是否需要认证
        chain.doFilter(request, response);
    }

    private void sendErrorResponse(HttpServletResponse response, String message) throws IOException {
        sendErrorResponse(response, HttpStatus.UNAUTHORIZED, 401, message);
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus.value());
        response.setContentType("application/json;charset=UTF-8");
        
        Result<Object> errorResult = Result.error(code, message);
        ObjectMapper objectMapper = new ObjectMapper();
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));
    }

    private boolean isLikelyDbDown(Throwable t) {
        // 不强依赖具体驱动类，避免编译期耦合：用类名/消息粗略判断
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            String cn = cur.getClass().getName();
            String msg = cur.getMessage();
            if (cn != null) {
                if (cn.contains("CommunicationsException")
                        || cn.contains("CJCommunicationsException")
                        || cn.contains("SQLNonTransientConnectionException")) {
                    return true;
                }
            }
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("communications link failure")
                        || m.contains("connection refused")
                        || m.contains("could not open connection")
                        || m.contains("connection is closed")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}