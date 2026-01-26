package fun.ai.studio.deploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.DeployProxyException;
import fun.ai.studio.common.Result;
import fun.ai.studio.config.DeployProxyProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API 服务侧轻量客户端：调用 Deploy 控制面（fun-ai-studio-deploy）。
 *
 * 注意：shared-secret 用于 API -> Deploy 内部鉴权（Deploy 侧校验 Header：X-DEPLOY-SECRET）。
 */
@Component
public class DeployClient {

    private final DeployProxyProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeployClient(DeployProxyProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled() && StringUtils.hasText(props.getBaseUrl());
    }

    public Map<String, Object> createJob(Map<String, Object> body) {
        return requestJson("POST", "/deploy/jobs", body, new TypeReference<Result<Map<String, Object>>>() {});
    }

    public Map<String, Object> getJob(String jobId) {
        return requestJson("GET", "/deploy/jobs/" + urlPath(jobId), null, new TypeReference<Result<Map<String, Object>>>() {});
    }

    public List<Map<String, Object>> listJobs(int limit) {
        String path = "/deploy/jobs?limit=" + limit;
        return requestJson("GET", path, null, new TypeReference<Result<List<Map<String, Object>>>>() {});
    }

    /**
     * 按 appId 查询部署历史。
     */
    public List<Map<String, Object>> listJobsByApp(String appId, int limit) {
        String path = "/deploy/jobs/by-app?appId=" + urlPath(appId) + "&limit=" + limit;
        return requestJson("GET", path, null, new TypeReference<Result<List<Map<String, Object>>>>() {});
    }

    public Map<String, Object> cancelJob(String jobId) {
        return requestJson("POST", "/deploy/jobs/" + urlPath(jobId) + "/cancel", Map.of(), new TypeReference<Result<Map<String, Object>>>() {});
    }

    public Map<String, Object> stopApp(Map<String, Object> body) {
        return requestJson("POST", "/deploy/apps/stop", body, new TypeReference<Result<Map<String, Object>>>() {});
    }

    public Map<String, Object> purgeApp(Map<String, Object> body) {
        return requestJson("POST", "/deploy/apps/purge", body, new TypeReference<Result<Map<String, Object>>>() {});
    }

    private <T> T requestJson(String method, String pathAndQuery, Object bodyObj, TypeReference<Result<T>> typeRef) {
        if (!isEnabled()) {
            throw new DeployProxyException("deploy-proxy 未启用或 base-url 未配置");
        }
        String m = (method == null ? "GET" : method).toUpperCase(Locale.ROOT);
        String url = joinUrl(props.getBaseUrl(), pathAndQuery);
        byte[] bodyBytes = new byte[0];
        if (bodyObj != null) {
            try {
                bodyBytes = objectMapper.writeValueAsBytes(bodyObj);
            } catch (Exception e) {
                throw new DeployProxyException("deploy request encode failed: " + e.getMessage());
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url));
        if ("GET".equals(m) || "HEAD".equals(m)) {
            b.method(m, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(m, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        }
        if (bodyObj != null) {
            b.header("Content-Type", "application/json");
        }
        // internal auth：API -> Deploy 共享密钥
        if (StringUtils.hasText(props.getSharedSecret())) {
            b.header("X-DEPLOY-SECRET", props.getSharedSecret());
        }

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeployProxyException("deploy request interrupted");
        } catch (Exception e) {
            throw new DeployProxyException("deploy request failed: " + e.getMessage());
        }

        try {
            Result<T> r = objectMapper.readValue(resp.body(), typeRef);
            if (r == null) throw new DeployProxyException("deploy response empty");
            if (r.getCode() == null || r.getCode() != 200) {
                throw new DeployProxyException("deploy error: code=" + r.getCode() + ", msg=" + r.getMessage());
            }
            return r.getData();
        } catch (DeployProxyException e) {
            throw e;
        } catch (Exception e) {
            String raw = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
            throw new DeployProxyException("deploy decode failed: http=" + resp.statusCode() + ", body=" + raw);
        }
    }

    private String joinUrl(String baseUrl, String pathAndQuery) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = (pathAndQuery == null) ? "" : pathAndQuery;
        if (!p.startsWith("/")) p = "/" + p;
        return b + p;
    }

    private String urlPath(String s) {
        // jobId 是 UUID 字符串，按路径拼接即可
        return s == null ? "" : s;
    }
}


