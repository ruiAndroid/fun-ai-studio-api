package fun.ai.studio.controller.admin;

import fun.ai.studio.common.Result;
import fun.ai.studio.config.WorkspaceNodeProxyProperties;
import fun.ai.studio.config.WorkspaceNodeProxySigner;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.workspace.WorkspaceNodeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 管理员工作空间文件管理接口
 * <p>
 * 提供管理员下载任意用户应用 ZIP 包的功能。
 * 请求会被转发到目标用户所在的 workspace-node（大机）执行。
 * </p>
 */
@RestController
@RequestMapping("/api/fun-ai/admin/workspace-files")
@Tag(name = "管理员工作空间文件", description = "管理员管理用户工作空间文件（需管理员权限）")
public class AdminWorkspaceFileController {
    private static final Logger log = LoggerFactory.getLogger(AdminWorkspaceFileController.class);

    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";
    private static final String HDR_PROXIED_BY = "X-WS-Proxied-By";
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    private final FunAiUserService funAiUserService;
    private final FunAiAppService funAiAppService;
    private final WorkspaceNodeResolver nodeResolver;
    private final WorkspaceNodeProxyProperties proxyProps;
    private final HttpClient httpClient;

    public AdminWorkspaceFileController(FunAiUserService funAiUserService,
                                       FunAiAppService funAiAppService,
                                       WorkspaceNodeResolver nodeResolver,
                                       WorkspaceNodeProxyProperties proxyProps) {
        this.funAiUserService = funAiUserService;
        this.funAiAppService = funAiAppService;
        this.nodeResolver = nodeResolver;
        this.proxyProps = proxyProps;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (proxyProps != null && proxyProps.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(proxyProps.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    @GetMapping("/download-zip")
    @Operation(summary = "下载应用目录（zip）",
            description = "管理员下载指定用户的应用目录。验证当前用户是管理员后，转发请求到 workspace-node 执行打包下载。")
    public void downloadAppZip(
            @Parameter(description = "目标用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否包含 node_modules（默认 false）") @RequestParam(defaultValue = "false") boolean includeNodeModules,
            HttpServletResponse response
    ) throws IOException {
        FunAiUser currentUser = getCurrentUser();
        if (currentUser == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, 401, "请先登录");
            return;
        }
        if (currentUser.getUserType() == null || currentUser.getUserType() != 1) {
            log.warn("非管理员用户尝试下载应用: userId={}, appId={}, currentUserId={}",
                    userId, appId, currentUser.getId());
            writeError(response, HttpStatus.FORBIDDEN, 403, "需要管理员权限");
            return;
        }

        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            writeError(response, HttpStatus.NOT_FOUND, 404, "应用不存在或不属于该用户");
            return;
        }

        FunAiWorkspaceNode node;
        try {
            node = nodeResolver.resolve(userId);
        } catch (Exception e) {
            log.error("解析 workspace-node 失败: userId={}, error={}", userId, e.getMessage(), e);
            writeError(response, HttpStatus.BAD_GATEWAY, 502, "无法解析用户所在节点: " + e.getMessage());
            return;
        }
        if (node == null || node.getApiBaseUrl() == null || node.getApiBaseUrl().isBlank()) {
            writeError(response, HttpStatus.BAD_GATEWAY, 502, "用户所在节点信息不完整");
            return;
        }

        String upstreamPath = "/api/fun-ai/workspace/files/download-zip";
        String query = String.format("userId=%d&appId=%d&includeNodeModules=%s",
                userId, appId, includeNodeModules);
        String upstreamUrl = joinUrl(node.getApiBaseUrl(), upstreamPath, query);

        String secret = proxyProps != null ? proxyProps.getSharedSecret() : null;
        if (secret == null || secret.isBlank()) {
            log.error("workspace-node-proxy.shared-secret 未配置");
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "代理配置错误");
            return;
        }

        long ts = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String canonical;
        try {
            canonical = WorkspaceNodeProxySigner.canonical("GET", upstreamPath, query, "", ts, nonce);
        } catch (Exception e) {
            log.error("签名生成失败: {}", e.getMessage(), e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "签名生成失败");
            return;
        }

        String sig;
        try {
            sig = WorkspaceNodeProxySigner.hmacSha256Base64(secret, canonical);
        } catch (Exception e) {
            log.error("HMAC 签名失败: {}", e.getMessage(), e);
            writeError(response, HttpStatus.INTERNAL_SERVER_ERROR, 500, "签名生成失败");
            return;
        }

        long readTimeout = proxyProps != null ? proxyProps.getReadTimeoutMs() : 30000;
        if (readTimeout <= 0) readTimeout = 30000;
        HttpRequest upstreamReq;
        try {
            upstreamReq = HttpRequest.newBuilder()
                    .uri(URI.create(upstreamUrl))
                    .timeout(Duration.ofMillis(readTimeout))
                    .header(HDR_TS, String.valueOf(ts))
                    .header(HDR_NONCE, nonce)
                    .header(HDR_SIG, sig)
                    .header(HDR_PROXIED_BY, "admin-controller")
                    .GET()
                    .build();
        } catch (Exception e) {
            log.error("构建上游请求失败: {}", e.getMessage(), e);
            writeError(response, HttpStatus.BAD_GATEWAY, 502, "构建请求失败: " + e.getMessage());
            return;
        }

        HttpResponse<InputStream> upstreamResp;
        try {
            upstreamResp = httpClient.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(response, HttpStatus.BAD_GATEWAY, 502, "请求被中断");
            return;
        } catch (Exception e) {
            log.error("转发请求失败: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            writeError(response, HttpStatus.BAD_GATEWAY, 502, "转发请求失败: " + e.getMessage());
            return;
        }

        String filename = "app_" + appId + ".zip";
        response.setStatus(HttpStatus.OK.value());
        response.setHeader("X-WS-Proxied", "1");
        response.setHeader("X-WS-Upstream", node.getApiBaseUrl());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment()
                        .filename(filename)
                        .build()
                        .toString());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try (InputStream in = upstreamResp.body()) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    response.getOutputStream().write(buffer, 0, bytesRead);
                }
                response.getOutputStream().flush();
            }
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, int code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<?> error = Result.error(code, message);
        response.getWriter().write(toJson(error));
    }

    private String toJson(Result<?> r) {
        int c = r == null || r.getCode() == null ? 500 : r.getCode();
        String m = r == null || r.getMessage() == null ? "" : r.getMessage();
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

    private FunAiUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String username = null;
        if (principal instanceof UserDetails ud) {
            username = ud.getUsername();
        } else if (principal instanceof String s) {
            username = s;
        }
        if (username == null || username.isBlank()) {
            return null;
        }
        return funAiUserService.findByUsername(username);
    }

    private String joinUrl(String baseUrl, String path, String query) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (path == null || path.isBlank()) ? "" : path;
        String q = (query == null || query.isBlank()) ? "" : ("?" + query);
        return b + p + q;
    }

    private String randomNonce() {
        byte[] b = new byte[16];
        SECURE_RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
