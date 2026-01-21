package fun.ai.studio.deploy;

import fun.ai.studio.common.Result;
import fun.ai.studio.config.DeployProxyProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class DeployAdminProxyClient {

    private final DeployProxyProperties props;
    private final WebClient webClient;

    public DeployAdminProxyClient(DeployProxyProperties props, WebClient.Builder builder) {
        this.props = props;
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

    public <T> Result<T> get(String path, MultiValueMap<String, String> params, String adminToken, ParameterizedTypeReference<Result<T>> type) {
        MultiValueMap<String, String> p = params == null ? new LinkedMultiValueMap<>() : params;
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(p).build())
                .headers(h -> h.addAll(buildHeaders(adminToken)))
                .retrieve()
                .bodyToMono(type)
                .timeout(Duration.ofSeconds(8))
                .onErrorReturn(Result.error("deploy-proxy 请求失败"))
                .block();
    }

    public <T> Result<T> post(String path, MultiValueMap<String, String> params, Object body, String adminToken, ParameterizedTypeReference<Result<T>> type) {
        MultiValueMap<String, String> p = params == null ? new LinkedMultiValueMap<>() : params;
        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path(path).queryParams(p).build())
                .headers(h -> h.addAll(buildHeaders(adminToken)))
                .bodyValue(body == null ? new Object() : body)
                .retrieve()
                .bodyToMono(type)
                .timeout(Duration.ofSeconds(15))
                .onErrorReturn(Result.error("deploy-proxy 请求失败"))
                .block();
    }
}



