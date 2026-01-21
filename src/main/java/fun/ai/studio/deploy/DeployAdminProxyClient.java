package fun.ai.studio.deploy;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.config.DeployProxyProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class DeployAdminProxyClient {

    private final DeployProxyProperties props;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DeployAdminProxyClient(DeployProxyProperties props, WebClient.Builder builder, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.webClient = builder
                .baseUrl(props == null ? "" : props.getBaseUrl())
                .build();
    }

    public boolean isEnabled() {
        return props != null && props.isEnabled();
    }

    public String baseUrl() {
        return props == null ? "" : props.getBaseUrl();
    }

    private HttpHeaders buildHeaders(String adminToken) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (adminToken != null && !adminToken.isBlank()) {
            h.set("X-Admin-Token", adminToken.trim());
        }
        if (props != null && props.getSharedSecret() != null && !props.getSharedSecret().isBlank()) {
            // 预留：Deploy 侧若启用内部鉴权可校验该 Header
            h.set("X-DEPLOY-SECRET", props.getSharedSecret().trim());
        }
        return h;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max) + "...(truncated)";
    }

    private <T> Result<T> decodeBody(String body, int httpStatus, ParameterizedTypeReference<Result<T>> type) {
        if (body == null || body.isBlank()) {
            return Result.error("deploy-proxy HTTP " + httpStatus + "（空响应）");
        }
        try {
            JavaType jt = (objectMapper == null)
                    ? null
                    : objectMapper.constructType(type.getType());
            if (jt == null) {
                return Result.error("deploy-proxy 响应解析失败：ObjectMapper 未注入，body=" + truncate(body, 200));
            }
            return objectMapper.readValue(body, jt);
        } catch (Exception e) {
            return Result.error("deploy-proxy HTTP " + httpStatus + " 响应解析失败：" + e.getMessage() + " body=" + truncate(body, 200));
        }
    }

    private <T> Mono<Result<T>> exchangeToResult(ClientResponse resp, ParameterizedTypeReference<Result<T>> type) {
        int status = resp.statusCode().value();
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> decodeBody(body, status, type));
    }

    public <T> Result<T> get(String path, MultiValueMap<String, String> params, String adminToken, ParameterizedTypeReference<Result<T>> type) {
        MultiValueMap<String, String> p = params == null ? new LinkedMultiValueMap<>() : params;
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(p).build())
                .headers(h -> h.addAll(buildHeaders(adminToken)))
                .exchangeToMono(resp -> exchangeToResult(resp, type))
                .timeout(Duration.ofSeconds(8))
                .onErrorResume(e -> Mono.just(Result.error("deploy-proxy 请求失败: " + e.getClass().getSimpleName() + ": " + e.getMessage())))
                .block();
    }

    public <T> Result<T> post(String path, MultiValueMap<String, String> params, Object body, String adminToken, ParameterizedTypeReference<Result<T>> type) {
        MultiValueMap<String, String> p = params == null ? new LinkedMultiValueMap<>() : params;
        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(p).build())
                .headers(h -> h.addAll(buildHeaders(adminToken)))
                .bodyValue(body == null ? new Object() : body)
                .exchangeToMono(resp -> exchangeToResult(resp, type))
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(e -> Mono.just(Result.error("deploy-proxy 请求失败: " + e.getClass().getSimpleName() + ": " + e.getMessage())))
                .block();
    }
}



