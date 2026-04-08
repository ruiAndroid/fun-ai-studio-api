package fun.ai.studio.controller.admin;

import fun.ai.studio.config.WorkspaceNodeProxyProperties;
import fun.ai.studio.config.WorkspaceNodeProxySigner;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.workspace.WorkspaceNodeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 管理接口：Workspace 文件操作（IP 白名单 + X-Admin-Token）
 *
 * 管理员代用户下载应用 zip 时，直接转发到 workspace-node 执行。
 */
@RestController
@RequestMapping("/api/fun-ai/admin/workspace/files")
@Tag(
        name = "Fun AI 管理端 - Workspace 文件",
        description = "管理后台：代用户操作 Workspace 文件（需 X-Admin-Token）\n\n"
                + "访问入口：\n"
                + "- Header：X-Admin-Token={{adminToken}}\n"
                + "- 来源 IP：需在 funai.admin.allowed-ips 白名单内"
)
public class AdminWorkspaceFileController {
    private static final Logger log = LoggerFactory.getLogger(AdminWorkspaceFileController.class);

    private static final String ADMIN_WORKSPACE_PATH = "/api/fun-ai/workspace/files/download-zip";
    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";
    private static final MediaType APPLICATION_ZIP = MediaType.parseMediaType("application/zip");

    private final WorkspaceNodeResolver nodeResolver;
    private final WorkspaceNodeProxyProperties proxyProps;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public AdminWorkspaceFileController(WorkspaceNodeResolver nodeResolver, WorkspaceNodeProxyProperties proxyProps) {
        this.nodeResolver = nodeResolver;
        this.proxyProps = proxyProps;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (proxyProps != null && proxyProps.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(proxyProps.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    @GetMapping("/download-zip")
    @Operation(summary = "代用户下载应用目录 zip", description = "管理员使用 X-Admin-Token 鉴权，代指定用户下载应用代码（跳过归属校验）")
    public ResponseEntity<StreamingResponseBody> downloadAppZip(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "是否包含 node_modules（默认 false）") @RequestParam(defaultValue = "false") boolean includeNodeModules
    ) {
        try {
            FunAiWorkspaceNode node = nodeResolver.resolve(userId);
            if (node == null || node.getApiBaseUrl() == null || node.getApiBaseUrl().isBlank()) {
                log.warn("admin download zip: cannot resolve node for userId={}", userId);
                return ResponseEntity.notFound().build();
            }

            String baseUrl = node.getApiBaseUrl().trim().replaceFirst("/+$", "");
            String path = ADMIN_WORKSPACE_PATH;
            String query = "userId=" + userId + "&appId=" + appId + "&includeNodeModules=" + includeNodeModules;

            String secret = proxyProps == null ? null : proxyProps.getSharedSecret();
            if (secret == null || secret.isBlank() || "CHANGE_ME_STRONG_SECRET".equals(secret)) {
                log.error("admin download zip: shared-secret not configured");
                return ResponseEntity.internalServerError().build();
            }

            long ts = Instant.now().getEpochSecond();
            String nonce = randomNonceHex();
            String bodySha = WorkspaceNodeProxySigner.sha256Hex(new byte[0]);
            String canonical = WorkspaceNodeProxySigner.canonical("GET", path, query, bodySha, ts, nonce);
            String sig = WorkspaceNodeProxySigner.hmacSha256Base64(secret, canonical);

            URI uri = URI.create(baseUrl + path + "?" + query);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .header(HDR_TS, String.valueOf(ts))
                    .header(HDR_NONCE, nonce)
                    .header(HDR_SIG, sig)
                    .header("Accept", "application/zip, application/octet-stream, */*")
                    .GET();
            if (proxyProps != null && proxyProps.getReadTimeoutMs() > 0) {
                reqBuilder.timeout(Duration.ofMillis(proxyProps.getReadTimeoutMs()));
            }
            HttpRequest upstreamReq = reqBuilder.build();

            HttpResponse<InputStream> upstreamResp;
            try {
                upstreamResp = httpClient.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("admin download zip: upstream interrupted, userId={}, appId={}", userId, appId);
                return ResponseEntity.internalServerError().build();
            } catch (Exception e) {
                log.error("admin download zip: upstream error, userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
                return ResponseEntity.status(502).build();
            }

            int status = upstreamResp.statusCode();
            String filename = "app_" + appId + ".zip";
            ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();

            StreamingResponseBody body = os -> {
                try (InputStream in = upstreamResp.body()) {
                    if (in != null) {
                        in.transferTo(os);
                        os.flush();
                    }
                }
            };

            return ResponseEntity.status(status)
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(APPLICATION_ZIP)
                    .body(body);

        } catch (Exception e) {
            log.error("admin download zip failed: userId={}, appId={}, error={}", userId, appId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String randomNonceHex() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
