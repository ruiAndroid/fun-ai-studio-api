package fun.ai.studio.controller.admin;

import fun.ai.studio.common.Result;
import fun.ai.studio.config.WorkspaceNodeProxyProperties;
import fun.ai.studio.config.WorkspaceNodeProxySigner;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.workspace.WorkspaceNodeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 管理员工作空间文件管理接口
 * <p>
 * 提供管理员下载任意用户应用 ZIP 包的功能。请求转发到 workspace-node 执行。
 * </p>
 */
@RestController
@RequestMapping("/api/fun-ai/admin/workspace-files")
@Tag(name = "管理员功能", description = "管理员工作空间文件管理和用户反馈管理（需管理员权限）")
public class AdminWorkspaceFileController {
    private static final Logger log = LoggerFactory.getLogger(AdminWorkspaceFileController.class);

    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

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

    /**
     * 管理员下载指定用户的应用 ZIP 包
     */
    @GetMapping("/download-zip")
    @Operation(summary = "下载应用目录（zip）",
            description = "管理员下载指定用户的应用目录。验证当前用户是管理员后，转发请求到 workspace-node 执行打包下载。")
    public ResponseEntity<StreamingResponseBody> downloadAppZip(
            @Parameter(description = "目标用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否包含 node_modules（默认 false）") @RequestParam(defaultValue = "false") boolean includeNodeModules
    ) {
        // 1. 验证当前登录用户为管理员
        FunAiUser currentUser = getCurrentUser();
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (currentUser.getUserType() == null || currentUser.getUserType() != 1) {
            log.warn("非管理员用户尝试下载应用: userId={}, appId={}, currentUserId={}",
                    userId, appId, currentUser.getId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // 2. 校验目标应用确实属于指定用户
        FunAiApp app = funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            return ResponseEntity.notFound().build();
        }

        // 3. 构建转发请求到 workspace-node
        String filename = "app_" + appId + ".zip";
        try {
            String upstreamPath = "/api/fun-ai/workspace/files/download-zip";
            String query = String.format("userId=%d&appId=%d&includeNodeModules=%s",
                    userId, appId, includeNodeModules);

            String baseUrl = nodeResolver.resolve(userId).getApiBaseUrl();
            String upstreamUrl = joinUrl(baseUrl, upstreamPath, query);

            // 生成签名
            String secret = proxyProps != null ? proxyProps.getSharedSecret() : null;
            if (secret == null || secret.isBlank()) {
                log.error("workspace-node-proxy.shared-secret 未配置");
                return ResponseEntity.internalServerError().build();
            }

            long ts = Instant.now().getEpochSecond();
            String nonce = randomNonce();
            String canonical = WorkspaceNodeProxySigner.canonical("GET", upstreamPath, query, "", ts, nonce);
            String sig = WorkspaceNodeProxySigner.hmacSha256Base64(secret, canonical);

            long readTimeout = proxyProps != null ? proxyProps.getReadTimeoutMs() : 30000;
            if (readTimeout <= 0) readTimeout = 30000;

            HttpRequest upstreamReq = HttpRequest.newBuilder()
                    .uri(URI.create(upstreamUrl))
                    .timeout(Duration.ofMillis(readTimeout))
                    .header(HDR_TS, String.valueOf(ts))
                    .header(HDR_NONCE, nonce)
                    .header(HDR_SIG, sig)
                    .GET()
                    .build();

            HttpResponse<InputStream> upstreamResp = httpClient.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());

            StreamingResponseBody body = outputStream -> {
                try (InputStream in = upstreamResp.body()) {
                    if (in != null) {
                        in.transferTo(outputStream);
                        outputStream.flush();
                    }
                }
            };

            ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .header("X-WS-Proxied", "1")
                    .header("X-WS-Upstream", baseUrl)
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(body);

        } catch (IllegalArgumentException e) {
            log.error("下载应用 zip 参数错误: userId={}, appId={}, error={}", userId, appId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("下载应用 zip 失败: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
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

    private final AtomicLong nonceCounter = new AtomicLong(0);

    private String randomNonce() {
        return String.format("%016x%016x", System.nanoTime(), nonceCounter.incrementAndGet());
    }
}
