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
import fun.ai.studio.entity.request.WorkspaceMongoFindRequest;
import fun.ai.studio.entity.request.WorkspaceMongoCreateCollectionRequest;
import fun.ai.studio.entity.request.WorkspaceMongoInsertOneRequest;
import fun.ai.studio.entity.request.WorkspaceMongoUpdateByIdRequest;
import fun.ai.studio.entity.request.WorkspaceMongoDeleteByIdRequest;
import fun.ai.studio.entity.request.WorkspaceMongoOpRequest;
import fun.ai.studio.entity.response.WorkspaceMongoCollectionsResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDocResponse;
import fun.ai.studio.entity.response.WorkspaceMongoFindResponse;
import fun.ai.studio.entity.response.WorkspaceGitStatusResponse;
import fun.ai.studio.entity.response.WorkspaceGitEnsureResponse;
import fun.ai.studio.entity.response.WorkspaceGitLogResponse;
import fun.ai.studio.entity.response.WorkspaceGitCommitPushResponse;
import fun.ai.studio.entity.response.WorkspaceGitRevertResponse;
import fun.ai.studio.entity.request.WorkspaceGitCommitPushRequest;
import fun.ai.studio.entity.response.WorkspaceMongoCreateCollectionResponse;
import fun.ai.studio.entity.response.WorkspaceMongoInsertOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoUpdateOneResponse;
import fun.ai.studio.entity.response.WorkspaceMongoDeleteOneResponse;
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
 * API 服务器（小机）侧轻量客户端：调用 Workspace 开发服务器（大机）workspace-node（带 HMAC 签名）。
 *
 * <p>用于裁剪 API 服务器（小机）的“容器/文件系统重操作”依赖：API 服务器（小机）业务域只做鉴权与聚合展示，重操作在 Workspace 开发服务器（大机）执行。</p>
 */
@Component
public class WorkspaceNodeClient {
    private static final String HDR_SIG = "X-WS-Signature";
    private static final String HDR_TS = "X-WS-Timestamp";
    private static final String HDR_NONCE = "X-WS-Nonce";

    private final WorkspaceNodeProxyProperties props;
    private final WorkspaceNodeResolver nodeResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceNodeClient(WorkspaceNodeProxyProperties props, ObjectMapper objectMapper, WorkspaceNodeResolver nodeResolver) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.nodeResolver = nodeResolver;
        HttpClient.Builder b = HttpClient.newBuilder();
        if (props != null && props.getConnectTimeoutMs() > 0) {
            b.connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        }
        this.httpClient = b.build();
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled()
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
     * 供业务域删除应用后通知 Workspace 开发服务器（大机）清理 workspace 目录（避免磁盘泄漏）。
     */
    public void cleanupOnAppDeleted(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/internal/maintenance/app-deleted";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        requestJson("POST", path, query, new byte[0], new TypeReference<Result<Object>>() {});
    }

    public WorkspaceMongoCollectionsResponse mongoCollections(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/mongo/collections";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        return requestJson("GET", path, query, null, new TypeReference<Result<WorkspaceMongoCollectionsResponse>>() {});
    }

    public WorkspaceMongoFindResponse mongoFind(Long userId, Long appId, WorkspaceMongoFindRequest req) {
        String path = "/api/fun-ai/workspace/mongo/find";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo find request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceMongoFindResponse>>() {});
    }

    public WorkspaceMongoDocResponse mongoDoc(Long userId, Long appId, String collection, String id) {
        String path = "/api/fun-ai/workspace/mongo/doc";
        String query = query(Map.of(
                "userId", String.valueOf(userId),
                "appId", String.valueOf(appId),
                "collection", collection == null ? "" : collection,
                "id", id == null ? "" : id
        ));
        return requestJson("GET", path, query, null, new TypeReference<Result<WorkspaceMongoDocResponse>>() {});
    }

    public WorkspaceMongoCreateCollectionResponse mongoCreateCollection(Long userId, Long appId, WorkspaceMongoCreateCollectionRequest req) {
        String path = "/api/fun-ai/workspace/mongo/create-collection";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo create-collection request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceMongoCreateCollectionResponse>>() {});
    }

    public WorkspaceMongoInsertOneResponse mongoInsertOne(Long userId, Long appId, WorkspaceMongoInsertOneRequest req) {
        String path = "/api/fun-ai/workspace/mongo/insert-one";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo insert-one request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceMongoInsertOneResponse>>() {});
    }

    public WorkspaceMongoUpdateOneResponse mongoUpdateById(Long userId, Long appId, WorkspaceMongoUpdateByIdRequest req) {
        String path = "/api/fun-ai/workspace/mongo/update-by-id";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo update-by-id request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceMongoUpdateOneResponse>>() {});
    }

    public WorkspaceMongoDeleteOneResponse mongoDeleteById(Long userId, Long appId, WorkspaceMongoDeleteByIdRequest req) {
        String path = "/api/fun-ai/workspace/mongo/delete-by-id";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo delete-by-id request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceMongoDeleteOneResponse>>() {});
    }

    public Object mongoOp(Long userId, Long appId, WorkspaceMongoOpRequest req) {
        String path = "/api/fun-ai/workspace/mongo/op";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(req);
        } catch (Exception e) {
            throw new RuntimeException("mongo op request encode failed: " + e.getMessage(), e);
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<Object>>() {});
    }

    /**
     * 获取 Git 状态（是否 git repo、是否 dirty、分支、commit 等）
     */
    public WorkspaceGitStatusResponse gitStatus(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/git/status";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        return requestJson("GET", path, query, null, new TypeReference<Result<WorkspaceGitStatusResponse>>() {});
    }

    /**
     * 确保 Git 同步（clone / pull / 返回 NEED_COMMIT 等）
     */
    public WorkspaceGitEnsureResponse gitEnsure(Long userId, Long appId) {
        String path = "/api/fun-ai/workspace/git/ensure";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        return requestJson("POST", path, query, new byte[0], new TypeReference<Result<WorkspaceGitEnsureResponse>>() {});
    }

    /**
     * 查看最近 N 次提交
     */
    public WorkspaceGitLogResponse gitLog(Long userId, Long appId, int limit) {
        String path = "/api/fun-ai/workspace/git/log";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId), "limit", String.valueOf(limit)));
        return requestJson("GET", path, query, null, new TypeReference<Result<WorkspaceGitLogResponse>>() {});
    }

    /**
     * 一键 commit + push
     */
    public WorkspaceGitCommitPushResponse gitCommitPush(Long userId, Long appId, WorkspaceGitCommitPushRequest request) {
        String path = "/api/fun-ai/workspace/git/commit-push";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId)));
        byte[] body = new byte[0];
        if (request != null) {
            try {
                body = objectMapper.writeValueAsBytes(request);
            } catch (Exception e) {
                throw new RuntimeException("git commit-push request encode failed: " + e.getMessage(), e);
            }
        }
        return requestJson("POST", path, query, body, new TypeReference<Result<WorkspaceGitCommitPushResponse>>() {});
    }

    /**
     * 回退到某一次提交（git revert）
     */
    public WorkspaceGitRevertResponse gitRevert(Long userId, Long appId, String commitSha) {
        String path = "/api/fun-ai/workspace/git/revert";
        String query = query(Map.of("userId", String.valueOf(userId), "appId", String.valueOf(appId), "commitSha", commitSha == null ? "" : commitSha));
        return requestJson("POST", path, query, new byte[0], new TypeReference<Result<WorkspaceGitRevertResponse>>() {});
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

        Long userId = extractUserIdFromQuery(q);
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        String baseUrl = nodeResolver.resolve(userId).getApiBaseUrl();
        URI uri = URI.create(joinUrl(baseUrl, p, q));
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

        Result<T> r;
        try {
            r = objectMapper.readValue(resp.body(), typeRef);
        } catch (Exception e) {
            String raw = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
            throw new RuntimeException("workspace-node decode failed: http=" + resp.statusCode() + ", body=" + raw, e);
        }
        if (r == null) {
            throw new RuntimeException("workspace-node response is empty");
        }
        // 业务错误：直接抛 IllegalArgumentException，让上层 controller 走“无堆栈的友好错误”分支，避免污染日志
        if (r.getCode() == null || r.getCode() != 200) {
            String msg = r.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = "workspace-node error: code=" + r.getCode();
            }
            throw new IllegalArgumentException(msg);
        }
        return r.getData();
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

    private Long extractUserIdFromQuery(String query) {
        if (!StringUtils.hasText(query)) return null;
        // 简化解析：只需找 userId=xxx
        String[] parts = query.split("&");
        for (String p : parts) {
            if (p == null) continue;
            int idx = p.indexOf('=');
            String k = idx < 0 ? p : p.substring(0, idx);
            String v = idx < 0 ? "" : p.substring(idx + 1);
            if (!"userId".equals(k)) continue;
            try {
                // query() 是 URLEncoder 生成的；userId 仅数字，不需要 decode
                return Long.parseLong(v);
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }
}


