package fun.ai.studio.gitea;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.config.GiteaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量 Gitea API Client（用于自动建仓库/授权）。
 *
 * - 认证：Authorization: token <adminToken>
 * - API: https://try.gitea.io/api/swagger
 */
@Component
public class GiteaClient {
    private static final Logger log = LoggerFactory.getLogger(GiteaClient.class);

    private final GiteaProperties props;
    private final ObjectMapper om;
    private final HttpClient httpClient;

    public GiteaClient(GiteaProperties props, ObjectMapper om) {
        this.props = props;
        this.om = om;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public boolean isEnabled() {
        return props != null
                && props.isEnabled()
                && StringUtils.hasText(props.getBaseUrl())
                && StringUtils.hasText(props.getAdminToken())
                && StringUtils.hasText(props.getOwner());
    }

    public boolean ensureOrgRepo(String owner, String repo, boolean isPrivate, boolean autoInit, String defaultBranch) {
        if (!isEnabled()) return false;
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) return false;

        // quick exists check
        int exists = requestStatus("GET", "/api/v1/repos/" + urlPath(owner) + "/" + urlPath(repo), null);
        if (exists == 200) return true;

        // create
        Map<String, Object> body = Map.of(
                "name", repo,
                "private", isPrivate,
                "auto_init", autoInit,
                "default_branch", (defaultBranch == null ? "" : defaultBranch)
        );
        int created = requestStatus("POST", "/api/v1/orgs/" + urlPath(owner) + "/repos", body);
        // 201 created; 409 already exists
        return created == 201 || created == 409;
    }

    public Long findTeamIdByName(String org, String teamName) {
        if (!isEnabled()) return null;
        if (!StringUtils.hasText(org) || !StringUtils.hasText(teamName)) return null;
        List<Map<String, Object>> teams = requestJson("GET", "/api/v1/orgs/" + urlPath(org) + "/teams", null,
                new TypeReference<List<Map<String, Object>>>() {});
        if (teams == null) return null;
        for (Map<String, Object> t : teams) {
            if (t == null) continue;
            Object n = t.get("name");
            if (n != null && teamName.equals(String.valueOf(n))) {
                Object id = t.get("id");
                if (id instanceof Number) return ((Number) id).longValue();
                try {
                    return Long.parseLong(String.valueOf(id));
                } catch (Exception ignore) {
                }
            }
        }
        return null;
    }

    public boolean grantTeamRepo(long teamId, String org, String repo) {
        if (!isEnabled()) return false;
        if (teamId <= 0 || !StringUtils.hasText(org) || !StringUtils.hasText(repo)) return false;
        int code = requestStatus("PUT", "/api/v1/teams/" + teamId + "/repos/" + urlPath(org) + "/" + urlPath(repo), null);
        // 204 no content (success)
        return code == 204 || code == 201 || code == 200;
    }

    public boolean addCollaboratorReadOnly(String owner, String repo, String username) {
        return addCollaborator(owner, repo, username, "read");
    }

    public boolean addCollaboratorWrite(String owner, String repo, String username) {
        return addCollaborator(owner, repo, username, "write");
    }

    /**
     * 删除仓库（幂等）：成功返回 true；仓库不存在（404）也返回 true。
     */
    public boolean deleteRepo(String owner, String repo) {
        if (!isEnabled()) return false;
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo)) return false;
        int code = requestStatus("DELETE", "/api/v1/repos/" + urlPath(owner) + "/" + urlPath(repo), null);
        // 204 no content (deleted) / 404 not found (already gone)
        return code == 204 || code == 404;
    }

    /**
     * 初始化仓库的模板文件（best-effort）：若文件不存在则创建；若已存在则跳过。
     *
     * @param owner 组织/owner
     * @param repo  仓库名
     * @param branch 分支（默认 main）
     * @param path  文件路径（例如 Dockerfile）
     * @param content 文件内容（UTF-8）
     * @param message commit message
     */
    public boolean ensureFile(String owner, String repo, String branch, String path, String content, String message) {
        if (!isEnabled()) return false;
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || !StringUtils.hasText(path)) return false;
        String b = (branch == null || branch.isBlank()) ? "main" : branch.trim();
        String p = path.trim();

        // exists check: GET /contents/{path}?ref=branch
        int exists = requestStatus("GET",
                "/api/v1/repos/" + urlPath(owner) + "/" + urlPath(repo) + "/contents/" + urlPath(p) + "?ref=" + urlPath(b),
                null);
        if (exists == 200) return true;

        // create: POST /contents/{path}  (Gitea also supports PUT; POST is documented for create)
        String msg = (message == null || message.isBlank()) ? ("init " + p) : message;
        String c = content == null ? "" : content;
        String b64 = Base64.getEncoder().encodeToString(c.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = Map.of(
                "branch", b,
                "content", b64,
                "message", msg
        );
        int created = requestStatus("POST",
                "/api/v1/repos/" + urlPath(owner) + "/" + urlPath(repo) + "/contents/" + urlPath(p),
                body);
        return created == 201 || created == 200 || created == 204;
    }

    private boolean addCollaborator(String owner, String repo, String username, String permission) {
        if (!isEnabled()) return false;
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repo) || !StringUtils.hasText(username)) return false;
        String p = (permission == null ? "read" : permission.trim());
        Map<String, Object> body = Map.of("permission", p);
        int code = requestStatus("PUT", "/api/v1/repos/" + urlPath(owner) + "/" + urlPath(repo) + "/collaborators/" + urlPath(username), body);
        return code == 204 || code == 201 || code == 200;
    }

    private int requestStatus(String method, String path, Object bodyObj) {
        HttpResponse<byte[]> resp = request(method, path, bodyObj);
        return resp == null ? 0 : resp.statusCode();
    }

    private <T> T requestJson(String method, String path, Object bodyObj, TypeReference<T> typeRef) {
        HttpResponse<byte[]> resp = request(method, path, bodyObj);
        if (resp == null) return null;
        try {
            return om.readValue(resp.body(), typeRef);
        } catch (Exception e) {
            String raw = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
            log.warn("gitea decode failed: http={}, path={}, body={}", resp.statusCode(), path, raw);
            return null;
        }
    }

    private HttpResponse<byte[]> request(String method, String pathAndQuery, Object bodyObj) {
        if (!isEnabled()) return null;
        String m = (method == null ? "GET" : method).toUpperCase(Locale.ROOT);
        String url = joinUrl(props.getBaseUrl(), pathAndQuery);
        byte[] bodyBytes = new byte[0];
        if (bodyObj != null) {
            try {
                bodyBytes = om.writeValueAsBytes(bodyObj);
            } catch (Exception e) {
                throw new RuntimeException("gitea request encode failed: " + e.getMessage());
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10));
        if ("GET".equals(m) || "HEAD".equals(m)) {
            b.method(m, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(m, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        }
        if (bodyObj != null) {
            b.header("Content-Type", "application/json");
        }
        b.header("Authorization", "token " + props.getAdminToken().trim());

        try {
            return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("gitea request failed: {} {} err={}", m, url, e.getMessage());
            return null;
        }
    }

    private String joinUrl(String baseUrl, String pathAndQuery) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (pathAndQuery == null) ? "" : pathAndQuery;
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }

    private String urlPath(String s) {
        // org/repo/userName 一般不含特殊字符；这里保持简单（避免引入 URLEncoder 影响 slash）
        return s == null ? "" : s;
    }
}


