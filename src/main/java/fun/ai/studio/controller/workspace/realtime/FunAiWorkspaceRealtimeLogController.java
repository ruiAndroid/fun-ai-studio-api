package fun.ai.studio.controller.workspace.realtime;

import fun.ai.studio.config.WorkspaceNodeProxyProperties;
import fun.ai.studio.config.WorkspaceNodeProxySigner;
import fun.ai.studio.entity.FunAiApp;
import fun.ai.studio.service.FunAiAppService;
import fun.ai.studio.workspace.WorkspaceNodeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/fun-ai/workspace/realtime")
@Tag(name = "Fun AI Workspace 实时通道", description = "仅保留日志拉取接口（非实时）")
public class FunAiWorkspaceRealtimeLogController {
    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

    private final FunAiAppService funAiAppService;
    private final WorkspaceNodeProxyProperties proxyProperties;
    private final WorkspaceNodeResolver nodeResolver;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public FunAiWorkspaceRealtimeLogController(
            FunAiAppService funAiAppService,
            WorkspaceNodeProxyProperties proxyProperties,
            WorkspaceNodeResolver nodeResolver
    ) {
        this.funAiAppService = funAiAppService;
        this.proxyProperties = proxyProperties;
        this.nodeResolver = nodeResolver;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (proxyProperties != null && proxyProperties.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(proxyProperties.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    @GetMapping(path = "/log", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "获取运行日志文件（非实时）",
            description = "直接返回对应日志文件内容（不做 SSE 增量推送）。优先按 type+appId 选择最新的日志文件；type 取 BUILD/INSTALL/PREVIEW。"
    )
    public ResponseEntity<byte[]> getLog(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "日志类型（BUILD/INSTALL/PREVIEW），默认 PREVIEW") @RequestParam(required = false) String type,
            @Parameter(description = "可选：仅返回末尾 N 字节（用于大日志快速查看），默认 0=返回全量") @RequestParam(defaultValue = "0") long tailBytes
    ) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        FunAiApp app = funAiAppService == null ? null : funAiAppService.getAppByIdAndUserId(appId, userId);
        if (app == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
        if (proxyProperties == null || !proxyProperties.isEnabled()
                || !StringUtils.hasText(proxyProperties.getSharedSecret())
                || "CHANGE_ME_STRONG_SECRET".equals(proxyProperties.getSharedSecret())) {
            throw new IllegalStateException("workspace-node proxy 未启用/密钥未配置");
        }

        String baseUrl = nodeResolver.resolve(userId).getApiBaseUrl();
        String path = "/api/fun-ai/workspace/realtime/log";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("userId", String.valueOf(userId));
        params.put("appId", String.valueOf(appId));
        if (type != null && !type.isBlank()) {
            params.put("type", type.trim());
        }
        params.put("tailBytes", String.valueOf(Math.max(0L, tailBytes)));
        String query = buildQuery(params);

        HttpResponse<InputStream> resp = requestStream("GET", baseUrl, path, query);
        if (resp.statusCode() != 200) {
            String raw;
            try (InputStream in = resp.body()) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                raw = "workspace-node error: http=" + resp.statusCode();
            }
            throw new IllegalArgumentException(raw);
        }

        byte[] bytes;
        try (InputStream in = resp.body()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("workspace-node response read failed: " + e.getMessage(), e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
        headers.add("X-Accel-Buffering", "no");
        headers.add("X-WS-Upstream", baseUrl);
        headers.add("X-WS-Log-Proxy", "api");
        String ct = resp.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null);
        if (ct != null && !ct.isBlank()) {
            try {
                headers.setContentType(MediaType.parseMediaType(ct));
            } catch (Exception ignore) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private HttpResponse<InputStream> requestStream(String method, String baseUrl, String path, String query) {
        byte[] body = new byte[0];
        long ts = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String bodySha;
        try {
            bodySha = WorkspaceNodeProxySigner.sha256Hex(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String canonical = WorkspaceNodeProxySigner.canonical(method, path, query, bodySha, ts, nonce);
        String sig;
        try {
            sig = WorkspaceNodeProxySigner.hmacSha256Base64(proxyProperties.getSharedSecret(), canonical);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        URI uri = URI.create(joinUrl(baseUrl, path, query));
        HttpRequest.Builder reqB = HttpRequest.newBuilder().uri(uri).GET();
        if (proxyProperties.getReadTimeoutMs() > 0) {
            reqB.timeout(Duration.ofMillis(proxyProperties.getReadTimeoutMs()));
        }
        reqB.header(HDR_TS, String.valueOf(ts));
        reqB.header(HDR_NONCE, nonce);
        reqB.header(HDR_SIG, sig);

        try {
            return httpClient.send(reqB.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("workspace-node request interrupted");
        } catch (Exception e) {
            throw new RuntimeException("workspace-node request failed: " + e.getMessage(), e);
        }
    }

    private String joinUrl(String baseUrl, String path, String query) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (path == null || path.isBlank()) ? "" : path;
        String q = (query == null || query.isBlank()) ? "" : ("?" + query);
        return b + p + q;
    }

    private String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEnc(e.getKey())).append('=').append(urlEnc(e.getValue()));
        }
        return sb.toString();
    }

    private String urlEnc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private String randomNonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}

