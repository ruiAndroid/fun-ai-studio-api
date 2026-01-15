package fun.ai.studio.workspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.config.WorkspaceNodeProxyProperties;
import fun.ai.studio.config.WorkspaceNodeProxySigner;
import fun.ai.studio.entity.response.FunAiWorkspaceFileNode;
import fun.ai.studio.entity.response.FunAiWorkspaceFileTreeResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceProjectDirResponse;
import fun.ai.studio.entity.response.FunAiWorkspaceRunStatusResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 小机侧轻量客户端：调用大机 workspace-node（带 HMAC 签名）。
 *
 * <p>用于裁剪小机的“容器/文件系统重操作”依赖：小机业务域只做鉴权与聚合展示，重操作在大机执行。</p>
 */
@Component
public class WorkspaceNodeClient {
    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

    private final WorkspaceNodeProxyProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceNodeClient(WorkspaceNodeProxyProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (props != null && props.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled()
                && StringUtils.hasText(props.getBaseUrl())
                && StringUtils.hasText(props.getSharedSecret())
                && !"CHANGE_ME_STRONG_SECRET".equals(props.getSharedSecret());
    }

    public FunAiWorkspaceProjectDirResponse ensureDir(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/files/ensure-dir";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        return requestJson("POST", path, query, new byte[0], new TypeReference<Result<FunAiWorkspaceProjectDirResponse>>() {});
    }

    public FunAiWorkspaceRunStatusResponse getRunStatus(Long userId) {
        String path = "/api/fun-ai/workspace/run/status";
        String query = query(Map.of("userId", String.valueOf(userId)));
        return requestJson("GET", path, query, null, new TypeReference<Result<FunAiWorkspaceRunStatusResponse>>() {});
    }

    public boolean hasPackageJson(Long userId, Long appId) {
        FunAiWorkspaceFileTreeResponse tree = getFileTree(userId, appId, ".", 2, 2000);
        if (tree == null || tree.getNodes() == null) return false;
        return containsPackageJson(tree.getNodes());
    }

    public FunAiWorkspaceFileTreeResponse getFileTree(Long userId, Long appId, String path, Integer maxDepth, Integer maxEntries) {
        String p = "/api/fun-ai/workspace/files/tree";
        String query = query(Map.of(
                "userId", String.valueOf(userId),
                "appId", String.valueOf(appId),
                "path", path == null ? "." : path,
                "maxDepth", String.valueOf(maxDepth == null ? 2 : maxDepth),
                "maxEntries", String.valueOf(maxEntries == null ? 2000 : maxEntries)
        ));
        return requestJson("GET", p, query, null, new TypeReference<Result<FunAiWorkspaceFileTreeResponse>>() {});
    }

    /**
     * 供业务域删除应用后通知大机清理 workspace 目录（避免磁盘泄漏）。
     */
    public void cleanupOnAppDeleted(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/internal/maintenance/app-deleted";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        requestJson("POST", path, query, new byte[0], new TypeReference<Result<Object>>() {});
    }

    private boolean containsPackageJson(List<FunAiWorkspaceFileNode> nodes) {
        for (FunAiWorkspaceFileNode n : nodes) {
            if (n == null) continue;
            if ("package.json".equals(n.getName())) return true;
            if (n.getChildren() != null && !n.getChildren().isEmpty()) {
                if (containsPackageJson(n.getChildren())) return true;
            }
        }
        return false;
    }

    private <T> T requestJson(String method, String path, String query, byte[] body, TypeReference<Result<T>> typeRef) {
        if (!isEnabled()) {
            throw new IllegalStateException("workspace-node client disabled");
        }
        if (method == null) method = "GET";
        String m = method.toUpperCase(Locale.ROOT);
        String p = path == null ? "" : path;
        String q = query == null ? "" : query;

        byte[] b = body;
        if (b == null) b = new byte[0];

        long ts = Instant.now().getEpochSecond();
        String nonce = randomNonce();
        String bodySha;
        try {
            bodySha = WorkspaceNodeProxySigner.sha256Hex(b);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        String canonical = WorkspaceNodeProxySigner.canonical(m, p, q, bodySha, ts, nonce);
        String sig;
        try {
            sig = WorkspaceNodeProxySigner.hmacSha256Base64(props.getSharedSecret(), canonical);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        URI uri = URI.create(joinUrl(props.getBaseUrl(), p, q));
        HttpRequest.Builder reqB = HttpRequest.newBuilder().uri(uri);
        if (props.getReadTimeoutMs() > 0) {
            reqB.timeout(Duration.ofMillis(props.getReadTimeoutMs()));
        }
        if ("GET".equals(m) || "HEAD".equals(m)) {
            reqB.method(m, HttpRequest.BodyPublishers.noBody());
        } else {
            reqB.method(m, HttpRequest.BodyPublishers.ofByteArray(b));
        }

        reqB.header(HDR_TS, String.valueOf(ts));
        reqB.header(HDR_NONCE, nonce);
        reqB.header(HDR_SIG, sig);

        // 内容类型：仅在有 body 时设置 JSON
        if (b.length > 0) {
            reqB.header("Content-Type", "application/json");
        }

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(reqB.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("workspace-node request interrupted");
        } catch (Exception e) {
            throw new RuntimeException("workspace-node request failed: " + e.getMessage(), e);
        }

        try {
            Result<T> r = objectMapper.readValue(resp.body(), typeRef);
            if (r == null) {
                throw new RuntimeException("workspace-node response is empty");
            }
            if (r.getCode() == null || r.getCode() != 200) {
                throw new RuntimeException("workspace-node error: code=" + r.getCode() + ", msg=" + r.getMessage());
            }
            return r.getData();
        } catch (Exception e) {
            String raw = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
            throw new RuntimeException("workspace-node decode failed: http=" + resp.statusCode() + ", body=" + raw, e);
        }
    }

    private String joinUrl(String baseUrl, String path, String query) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (path == null || path.isBlank()) ? "" : path;
        String q = (query == null || query.isBlank()) ? "" : ("?" + query);
        return b + p + q;
    }

    private String randomNonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String query(Map<String, String> params) {
        // 固定顺序无强约束（只要 canonical 用的与最终 URL 一致）；这里按插入顺序构建（Map.of 是固定顺序实现不保证，但影响不大）。
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
}


