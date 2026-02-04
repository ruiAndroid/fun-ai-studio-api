package fun.ai.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * /api/fun-ai/admin/** 管理接口鉴权：
 * - 来源 IP 在白名单内
 * - Header 携带 X-Admin-Token
 *
 * 注意：不要依赖 Spring Security/JWT（运维独立控制）。
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/fun-ai/admin/";
    private static final String WS_NODE_HEARTBEAT = "/api/fun-ai/admin/workspace-nodes/heartbeat";
    private static final String HDR = "X-Admin-Token";

    private final AdminSecurityProperties props;

    public AdminAuthFilter(AdminSecurityProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request == null) return true;
        String uri = request.getRequestURI();
        if (!StringUtils.hasText(uri) || !uri.startsWith(PREFIX)) return true;
        return props != null && !props.isEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // workspace-node 心跳使用独立鉴权（X-WS-Node-Token），不走 admin token
        String uri = request == null ? null : request.getRequestURI();
        if (WS_NODE_HEARTBEAT.equals(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (props == null || !props.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 注意：在 Nginx 反代到本机 127.0.0.1:8080 的场景下，remoteAddr 可能是 127.0.0.1；
        // 同时也会有 X-Forwarded-For（真实客户端 IP）。
        // 为了兼容这类部署：只要 remoteAddr 或 forwarded clientIp 任意一个命中白名单即可。
        String remoteIp = request.getRemoteAddr();
        String forwardedIp = clientIp(request);
        if (!isAllowedIp(remoteIp, props.getAllowedIps()) && !isAllowedIp(forwardedIp, props.getAllowedIps())) {
            denyAsJson(response, 403,
                    "admin forbidden: ip not allowed"
                            + " (remoteIp=" + safe(remoteIp)
                            + ", forwardedIp=" + safe(forwardedIp) + ")");
            return;
        }

        String expected = props.getToken();
        if (!StringUtils.hasText(expected) || "CHANGE_ME_STRONG_ADMIN_TOKEN".equals(expected)) {
            denyAsJson(response, 500, "admin token not configured");
            return;
        }

        String got = request.getHeader(HDR);
        if (!expected.equals(got)) {
            denyAsJson(response, 401, "admin unauthorized");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedIp(String ip, List<String> allowed) {
        if (!StringUtils.hasText(ip)) return false;
        if (allowed == null || allowed.isEmpty()) return false;
        for (String a : allowed) {
            if (!StringUtils.hasText(a)) continue;
            String rule = a.trim();
            if (rule.equals("*") || rule.equals("0.0.0.0/0") || rule.equals("::/0")) return true;
            if (rule.contains("/")) {
                if (cidrMatch(ip, rule)) return true;
                continue;
            }
            if (ip.equals(rule)) return true;
        }
        return false;
    }

    private boolean cidrMatch(String ip, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) return false;
        try {
            InetAddress addr = InetAddress.getByName(ip);
            InetAddress net = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] addrBytes = addr.getAddress();
            byte[] netBytes = net.getAddress();
            if (addrBytes.length != netBytes.length) return false;
            if (prefix <= 0) return true;
            int maxBits = addrBytes.length * 8;
            if (prefix > maxBits) return false;
            int fullBytes = prefix / 8;
            int remaining = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != netBytes[i]) return false;
            }
            if (remaining == 0) return true;
            int mask = 0xFF << (8 - remaining);
            int addrByte = addrBytes[fullBytes] & 0xFF;
            int netByte = netBytes[fullBytes] & 0xFF;
            return (addrByte & mask) == (netByte & mask);
        } catch (Exception ignore) {
            return false;
        }
    }

    private String clientIp(HttpServletRequest request) {
        // 入口 Nginx/网关场景：优先信任 X-Forwarded-For 的第一个值（如果你不想信任，可只用 remoteAddr）
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String first = xff.split(",")[0].trim();
            if (StringUtils.hasText(first)) return first;
        }
        String xri = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xri)) return xri.trim();
        return request.getRemoteAddr();
    }

    /**
     * nodes-admin.html 会直接读取 response.text() 并 JSON.parse，
     * 因此这里必须返回 JSON（即使 HTTP status 是 401/403）。
     */
    private void denyAsJson(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(toJson(code, msg));
    }

    private String toJson(int code, String msg) {
        int c = code <= 0 ? 500 : code;
        String m = msg == null ? "" : msg;
        return "{\"code\":" + c + ",\"message\":\"" + escapeJson(m) + "\",\"data\":null}";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String safe(String s) {
        if (!StringUtils.hasText(s)) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }
}


