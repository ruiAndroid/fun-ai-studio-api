package fun.ai.studio.controller.deploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.deploy.DeployClient;
import fun.ai.studio.entity.FunAiUser;
import fun.ai.studio.service.FunAiUserService;
import fun.ai.studio.service.FunAiAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deploy Mongo Explorer（部署态数据库查看）
 *
 * <p>前端页面：/deploy-mongo.html</p>
 *
 * <p>实现方式：API 服务负责鉴权 + appOwned 校验，然后根据 Deploy 控制面（7002）的 placement/job 信息解析出
 * runtime-agent（7005）的 agentBaseUrl，再把请求转发到 runtime-agent 的 /api/fun-ai/deploy/mongo/**。</p>
 *
 * <p>为什么需要这个 Controller？否则 /api/fun-ai/deploy/mongo/** 会落到静态资源处理器并报：
 * "No static resource api/fun-ai/deploy/mongo/collections"</p>
 */
@RestController
@RequestMapping("/api/fun-ai/deploy/mongo")
@Tag(name = "Fun AI Deploy Mongo Explorer", description = "Mongo Explorer（部署态：经 API 校验后转发到 runtime-agent 执行）")
public class FunAiDeployMongoController {
    private static final Logger log = LoggerFactory.getLogger(FunAiDeployMongoController.class);

    private final FunAiAppService funAiAppService;
    private final FunAiUserService funAiUserService;
    private final DeployClient deployClient;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public FunAiDeployMongoController(FunAiAppService funAiAppService,
                                      FunAiUserService funAiUserService,
                                      DeployClient deployClient,
                                      ObjectMapper objectMapper) {
        this.funAiAppService = funAiAppService;
        this.funAiUserService = funAiUserService;
        this.deployClient = deployClient;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate(buildFactory());
    }

    private SimpleClientHttpRequestFactory buildFactory() {
        // 对 runtime-agent 的请求：快速失败，避免卡住 API 线程
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        f.setReadTimeout((int) Duration.ofSeconds(15).toMillis());
        return f;
    }

    private Long resolveUserId(Long userId) {
        if (userId != null) return userId;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return null;
            Object principal = auth.getPrincipal();
            String username = null;
            if (principal instanceof UserDetails ud) {
                username = ud.getUsername();
            } else if (principal instanceof String s) {
                username = s;
            }
            if (!StringUtils.hasText(username)) return null;
            if (funAiUserService == null) return null;
            FunAiUser u = funAiUserService.findByUsername(username);
            return u == null ? null : u.getId();
        } catch (Exception ignore) {
            return null;
        }
    }

    private void assertAppOwned(Long userId, Long appId) {
        if (userId == null || appId == null) {
            throw new IllegalArgumentException("userId/appId 不能为空");
        }
        if (funAiAppService == null || funAiAppService.getAppByIdAndUserId(appId, userId) == null) {
            throw new IllegalArgumentException("应用不存在或无权限操作");
        }
    }

    private String resolveRuntimeAgentBaseUrl(Long appId) {
        if (deployClient == null || !deployClient.isEnabled()) {
            throw new IllegalStateException("deploy-proxy 未启用或不可用（请检查 deploy-proxy.enabled/base-url）");
        }
        List<Map<String, Object>> jobs = deployClient.listJobsByApp(String.valueOf(appId), 1);
        if (jobs == null || jobs.isEmpty()) {
            throw new IllegalArgumentException("该应用暂无部署记录（请先完成一次部署）");
        }
        Map<String, Object> job = jobs.get(0);
        Object rnObj = job == null ? null : job.get("runtimeNode");
        if (!(rnObj instanceof Map<?, ?> rn)) {
            throw new IllegalStateException("deploy 控制面未返回 runtimeNode（无法解析 runtime-agent 落点）");
        }
        Object ab = rn.get("agentBaseUrl");
        String agentBaseUrl = ab == null ? null : String.valueOf(ab).trim();
        if (!StringUtils.hasText(agentBaseUrl)) {
            throw new IllegalStateException("runtimeNode.agentBaseUrl 为空（请检查 runtime 节点注册/心跳）");
        }
        // normalize
        while (agentBaseUrl.endsWith("/")) agentBaseUrl = agentBaseUrl.substring(0, agentBaseUrl.length() - 1);
        return agentBaseUrl;
    }

    private Result<Object> proxyToRuntimeAgent(String method, String agentBaseUrl, String path, String query, byte[] bodyBytes) {
        String m = (method == null ? "GET" : method).toUpperCase(Locale.ROOT);
        String p = (path == null ? "" : path);
        if (!p.startsWith("/")) p = "/" + p;
        String q = (query == null || query.isBlank()) ? "" : ("?" + query);

        byte[] body = bodyBytes == null ? new byte[0] : bodyBytes;
        // FastAPI 对 required body 的接口：若 Content-Length=0 会直接报 "Field required"（loc=[body]）
        // 这里兜底：非 GET/HEAD 且 body 为空时补一个 {}，让错误更可读（例如缺 collection 字段）
        if (!"GET".equals(m) && !"HEAD".equals(m) && body.length == 0) {
            body = "{}".getBytes(StandardCharsets.UTF_8);
        }
        String url = agentBaseUrl + p + q;
        // 仅对 deploy mongo 场景做轻量诊断：打印 body 长度（不打印内容，避免敏感数据落盘）
        if (log.isInfoEnabled() && path != null && path.contains("/deploy/mongo")) {
            log.info("deploy-mongo proxy -> runtime-agent: method={}, url={}, bodyLen={}", m, url, body.length);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        HttpMethod hm;
        try {
            hm = HttpMethod.valueOf(m);
        } catch (Exception ignore) {
            hm = HttpMethod.POST;
        }
        HttpEntity<byte[]> entity;
        if (HttpMethod.GET.equals(hm) || HttpMethod.HEAD.equals(hm)) {
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(body, headers);
        }

        ResponseEntity<byte[]> resp;
        try {
            resp = restTemplate.exchange(url, hm, entity, byte[].class);
        } catch (Exception e) {
            return Result.error("runtime-agent 请求失败：" + e.getMessage());
        }

        byte[] rawBytes = resp.getBody() == null ? new byte[0] : resp.getBody();
        String raw = new String(rawBytes, StandardCharsets.UTF_8);

        // 1) runtime-agent 正常结构：{code,message,data}（与 API Result 结构一致），尽量原样透传
        try {
            Result<Object> r = objectMapper.readValue(rawBytes, new TypeReference<Result<Object>>() {});
            if (r != null && r.getCode() != null) return r;
        } catch (Exception ignore) {
        }

        // 2) FastAPI HTTPException：通常是 {"detail":"..."}（尤其是 4xx/5xx）
        try {
            Map<String, Object> m2 = objectMapper.readValue(rawBytes, new TypeReference<Map<String, Object>>() {});
            if (m2 != null) {
                Object detail = m2.get("detail");
                if (detail != null) {
                    String msg = String.valueOf(detail);
                    return Result.error("runtime-agent: " + msg);
                }
            }
        } catch (Exception ignore) {
        }

        // 3) fallback：非标准响应
        int status = resp.getStatusCode().value();
        if (status >= 200 && status < 300) {
            return Result.error("runtime-agent 响应解析失败（非 Result/JSON）：body=" + truncate(raw, 300));
        }
        return Result.error("runtime-agent HTTP " + status + "：" + truncate(raw, 300));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (max <= 0) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    @GetMapping("/collections")
    @Operation(summary = "列出集合（部署态）", description = "列出 db_u{userId}_a{appId} 的集合列表（由 runtime-agent 执行）")
    public Result<Object> collections(
            @Parameter(description = "用户ID（可选：不传则从登录态推断）", required = false) @RequestParam(required = false) Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId
    ) {
        try {
            Long uid = resolveUserId(userId);
            assertAppOwned(uid, appId);
            String agentBaseUrl = resolveRuntimeAgentBaseUrl(appId);
            return proxyToRuntimeAgent("GET", agentBaseUrl, "/api/fun-ai/deploy/mongo/collections",
                    "userId=" + uid + "&appId=" + appId, null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("deploy mongo collections failed: userId={}, appId={}, err={}", userId, appId, e.getMessage(), e);
            return Result.error("deploy mongo collections failed: " + e.getMessage());
        }
    }

    @PostMapping("/find")
    @Operation(summary = "查询文档（部署态）", description = "find（由 runtime-agent 执行）")
    public Result<Object> find(
            @Parameter(description = "用户ID（可选：不传则从登录态推断）", required = false) @RequestParam(required = false) Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) String body
    ) {
        try {
            Long uid = resolveUserId(userId);
            assertAppOwned(uid, appId);
            String agentBaseUrl = resolveRuntimeAgentBaseUrl(appId);
            // 原样转发 JSON（避免 Object->JSON 二次序列化导致 body 丢失/变空）
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (log.isInfoEnabled()) {
                log.info("deploy-mongo incoming find: userId={}, appId={}, inBodyLen={}", uid, appId, bytes.length);
            }
            return proxyToRuntimeAgent("POST", agentBaseUrl, "/api/fun-ai/deploy/mongo/find",
                    "userId=" + uid + "&appId=" + appId, bytes);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("deploy mongo find failed: userId={}, appId={}, err={}", userId, appId, e.getMessage(), e);
            return Result.error("deploy mongo find failed: " + e.getMessage());
        }
    }

    @GetMapping("/doc")
    @Operation(summary = "读取单条文档（部署态）", description = "按 _id 查询单条文档（由 runtime-agent 执行）")
    public Result<Object> doc(
            @Parameter(description = "用户ID（可选：不传则从登录态推断）", required = false) @RequestParam(required = false) Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "集合名", required = true) @RequestParam String collection,
            @Parameter(description = "文档ID(_id)", required = true) @RequestParam String id
    ) {
        try {
            Long uid = resolveUserId(userId);
            assertAppOwned(uid, appId);
            String agentBaseUrl = resolveRuntimeAgentBaseUrl(appId);
            String q = "userId=" + uid + "&appId=" + appId
                    + "&collection=" + urlEnc(collection)
                    + "&id=" + urlEnc(id);
            return proxyToRuntimeAgent("GET", agentBaseUrl, "/api/fun-ai/deploy/mongo/doc", q, null);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("deploy mongo doc failed: userId={}, appId={}, err={}", userId, appId, e.getMessage(), e);
            return Result.error("deploy mongo doc failed: " + e.getMessage());
        }
    }

    @PostMapping("/insert-one")
    @Operation(summary = "插入一条文档（部署态）", description = "insertOne（由 runtime-agent 执行）")
    public Result<Object> insertOne(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) String body
    ) {
        return proxyPost(userId, appId, "/api/fun-ai/deploy/mongo/insert-one", body, "insert-one");
    }

    @PostMapping("/update-by-id")
    @Operation(summary = "按 _id 更新文档（部署态）", description = "updateById（由 runtime-agent 执行）")
    public Result<Object> updateById(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) String body
    ) {
        return proxyPost(userId, appId, "/api/fun-ai/deploy/mongo/update-by-id", body, "update-by-id");
    }

    @PostMapping("/delete-by-id")
    @Operation(summary = "按 _id 删除文档（部署态）", description = "deleteById（由 runtime-agent 执行）")
    public Result<Object> deleteById(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) String body
    ) {
        return proxyPost(userId, appId, "/api/fun-ai/deploy/mongo/delete-by-id", body, "delete-by-id");
    }

    @PostMapping("/create-collection")
    @Operation(summary = "创建集合（部署态）", description = "createCollection（由 runtime-agent 执行）")
    public Result<Object> createCollection(
            @Parameter(description = "用户ID", required = true) @RequestParam Long userId,
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @RequestBody(required = false) String body
    ) {
        return proxyPost(userId, appId, "/api/fun-ai/deploy/mongo/create-collection", body, "create-collection");
    }

    private Result<Object> proxyPost(Long userId, Long appId, String upstreamPath, String body, String op) {
        try {
            Long uid = resolveUserId(userId);
            assertAppOwned(uid, appId);
            String agentBaseUrl = resolveRuntimeAgentBaseUrl(appId);
            // 原样转发 JSON（避免二次序列化导致 body 丢失/变空）
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            return proxyToRuntimeAgent("POST", agentBaseUrl, upstreamPath,
                    "userId=" + uid + "&appId=" + appId, bytes);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("deploy mongo {} failed: userId={}, appId={}, err={}", op, userId, appId, e.getMessage(), e);
            return Result.error("deploy mongo " + op + " failed: " + e.getMessage());
        }
    }

    private String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}


