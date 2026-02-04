package fun.ai.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * workspace-node 节点心跳鉴权（不依赖 JWT）：可选 IP 白名单 + 共享密钥 Header。
 *
 * 仅保护节点心跳接口：/api/fun-ai/admin/workspace-nodes/heartbeat
 */
public class WorkspaceNodeRegistryAuthFilter extends OncePerRequestFilter {

    private static final String PATH = "/api/fun-ai/admin/workspace-nodes/heartbeat";
    private static final String HDR = "X-WS-Node-Token";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceNodeRegistryAuthFilter.class);

    private final WorkspaceNodeRegistryProperties props;

    public WorkspaceNodeRegistryAuthFilter(WorkspaceNodeRegistryProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) return true;
        String uri = request.getRequestURI();
        if (!StringUtils.hasText(uri) || !PATH.equals(uri)) return true;
        return props != null && !props.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (props == null || !props.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 诊断日志：用于确认 workspace-node 是直连 API 还是经由域名/LB 转发。
        // 注意：这里不读取 request body / parameter map，避免影响 multipart/file upload 等场景。
        try {
            String remoteIp = request.getRemoteAddr();
            String xff = request.getHeader("X-Forwarded-For");
            String xri = request.getHeader("X-Real-IP");
            String host = request.getHeader("Host");
            String ua = request.getHeader("User-Agent");
            log.info("workspace-node heartbeat incoming: remoteAddr={}, xff={}, xri={}, host={}, ua={}, contentLength={}",
                    remoteIp,
                    (StringUtils.hasText(xff) ? xff : "-"),
                    (StringUtils.hasText(xri) ? xri : "-"),
                    (StringUtils.hasText(host) ? host : "-"),
                    (StringUtils.hasText(ua) ? ua : "-"),
                    request.getContentLengthLong());
        } catch (Exception ignore) {
        }

        // 可选 IP 白名单：为空则不校验
        String remoteIp = request.getRemoteAddr();
        String forwardedIp = clientIp(request);
        List<String> allowed = props.getAllowedIps();
        if (allowed != null && !allowed.isEmpty()) {
            if (!isAllowedIp(remoteIp, allowed) && !isAllowedIp(forwardedIp, allowed)) {
                deny(response, 403, "workspace-node heartbeat forbidden: ip not allowed");
                return;
            }
        }

        String expected = props.getSharedSecret();
        if (!StringUtils.hasText(expected) || "CHANGE_ME_STRONG_SECRET".equals(expected)) {
            deny(response, 500, "workspace-node registry secret not configured");
            return;
        }
        String got = request.getHeader(HDR);
        if (!expected.equals(got)) {
            deny(response, 401, "workspace-node heartbeat unauthorized");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedIp(String ip, List<String> allowed) {
        if (!StringUtils.hasText(ip)) return false;
        if (allowed == null || allowed.isEmpty()) return false;
        for (String a : allowed) {
            if (!StringUtils.hasText(a)) continue;
            if (ip.equals(a.trim())) return true;
        }
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String first = xff.split(",")[0].trim();
            if (StringUtils.hasText(first)) return first;
        }
        String xri = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xri)) return xri.trim();
        return request.getRemoteAddr();
    }

    private void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code);
        resp.setContentType(MediaType.TEXT_PLAIN_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(msg == null ? "" : msg);
    }
}


