package fun.ai.studio.controller.admin;

import fun.ai.studio.common.Result;
import fun.ai.studio.deploy.DeployAdminProxyClient;
import fun.ai.studio.entity.request.AdminDeployDrainNodeRequest;
import fun.ai.studio.entity.request.AdminDeployReassignPlacementRequest;
import fun.ai.studio.entity.request.AdminUpsertDeployRuntimeNodeRequest;
import fun.ai.studio.entity.response.AdminDeployRuntimeNodeSummary;
import fun.ai.studio.entity.response.AdminDeployRunnerSummary;
import fun.ai.studio.entity.response.AdminDeployRuntimePlacementItem;
import fun.ai.studio.entity.response.AdminDeployRuntimePlacementsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deploy 节点管理（通过 deploy-proxy 转发到 Deploy 控制面：/admin/runtime-nodes/**）。
 */
@RestController
@RequestMapping("/api/fun-ai/admin/deploy-nodes")
@Tag(
        name = "Admin Deploy Nodes",
        description = "预留：Deploy 节点管理（后续部署集群）。\n\n"
                + "访问入口：\n"
                + "- http://{{公网ip}}/admin/nodes.html#token={{adminToken}}\n"
                + "- http://{{公网ip}}/admin/nodes-admin.html?mode=deploy#token={{adminToken}}\n\n"
                + "当前状态：可用（需开启 deploy-proxy.enabled=true 并配置 deploy-proxy.base-url）。\n\n"
                + "鉴权方式：\n"
                + "- Header：X-Admin-Token={{adminToken}}\n"
                + "- 来源 IP：需在 funai.admin.allowed-ips 白名单内"
)
public class AdminDeployNodeController {

    private final DeployAdminProxyClient proxy;

    public AdminDeployNodeController(DeployAdminProxyClient proxy) {
        this.proxy = proxy;
    }

    private <T> Result<T> guardEnabled() {
        if (proxy == null || !proxy.isEnabled()) {
            return Result.error("deploy-proxy 未启用，请在 application-prod.properties 配置 deploy-proxy.enabled=true 及 deploy-proxy.base-url=");
        }
        return null;
    }

    @GetMapping("/list")
    @Operation(summary = "Deploy runtime 节点列表（转发到 deploy 控制面）")
    public Result<List<AdminDeployRuntimeNodeSummary>> list(@RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        Result<List<AdminDeployRuntimeNodeSummary>> g = guardEnabled();
        if (g != null) return g;

        Result<List<Map<String, Object>>> res = proxy.get(
                "/admin/runtime-nodes/list",
                null,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }

        List<AdminDeployRuntimeNodeSummary> out = (res.getData() == null ? List.<Map<String, Object>>of() : res.getData()).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
        return Result.success(out);
    }

    @GetMapping("/health")
    @Operation(summary = "Deploy 控制面在线状态（转发到 deploy /internal/health）")
    public Result<Map<String, Object>> health(@RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        Result<Map<String, Object>> g = guardEnabled();
        if (g != null) return g;

        Result<Map<String, Object>> res = proxy.get(
                "/internal/health",
                null,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("proxyBaseUrl", proxy.baseUrl());
        out.put("deploy", res.getData());
        return Result.success(out);
    }

    @GetMapping("/runners/list")
    @Operation(summary = "Runner 在线状态列表（转发到 deploy /admin/runners/list）")
    public Result<List<AdminDeployRunnerSummary>> runners(@RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        Result<List<AdminDeployRunnerSummary>> g = guardEnabled();
        if (g != null) return g;

        Result<List<Map<String, Object>>> res = proxy.get(
                "/admin/runners/list",
                null,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        List<AdminDeployRunnerSummary> out = (res.getData() == null ? List.<Map<String, Object>>of() : res.getData()).stream()
                .map(this::toRunnerSummary)
                .collect(Collectors.toList());
        return Result.success(out);
    }

    @PostMapping("/upsert")
    @Operation(summary = "新增/更新 Deploy runtime 节点（转发到 deploy 控制面）")
    public Result<AdminDeployRuntimeNodeSummary> upsert(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestBody AdminUpsertDeployRuntimeNodeRequest req
    ) {
        Result<AdminDeployRuntimeNodeSummary> g = guardEnabled();
        if (g != null) return g;

        Result<Map<String, Object>> res = proxy.post(
                "/admin/runtime-nodes/upsert",
                null,
                req,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        return Result.success(toSummary(res.getData()));
    }

    @PostMapping("/set-enabled")
    @Operation(summary = "启用/禁用 Deploy runtime 节点（转发到 deploy 控制面）")
    public Result<String> setEnabled(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam String name,
            @RequestParam Integer enabled
    ) {
        Result<String> g = guardEnabled();
        if (g != null) return g;

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("name", name);
        params.add("enabled", String.valueOf(enabled != null && enabled == 1));
        Result<String> res = proxy.post(
                "/admin/runtime-nodes/set-enabled",
                params,
                Map.of(),
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        return res;
    }

    @GetMapping("/placements")
    @Operation(summary = "查询某节点的 placements（appId -> nodeId）（转发到 deploy 控制面）")
    public Result<AdminDeployRuntimePlacementsResponse> placements(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam Long nodeId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "200") int limit
    ) {
        Result<AdminDeployRuntimePlacementsResponse> g = guardEnabled();
        if (g != null) return g;

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("nodeId", String.valueOf(nodeId));
        params.add("offset", String.valueOf(Math.max(offset, 0)));
        params.add("limit", String.valueOf(Math.min(Math.max(limit, 1), 2000)));

        Result<Map<String, Object>> res = proxy.get(
                "/admin/runtime-nodes/placements",
                params,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        return Result.success(toPlacements(res.getData()));
    }

    @PostMapping("/reassign")
    @Operation(summary = "手工迁移某应用 placement 到指定节点（转发到 deploy 控制面）")
    public Result<String> reassign(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestBody AdminDeployReassignPlacementRequest req
    ) {
        Result<String> g = guardEnabled();
        if (g != null) return g;

        Result<String> res = proxy.post(
                "/admin/runtime-nodes/reassign",
                null,
                req,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        return res;
    }

    @PostMapping("/drain")
    @Operation(summary = "批量 drain：迁移 placements（转发到 deploy 控制面）")
    public Result<Map<String, Object>> drain(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestBody AdminDeployDrainNodeRequest req
    ) {
        Result<Map<String, Object>> g = guardEnabled();
        if (g != null) return g;

        Result<Map<String, Object>> res = proxy.post(
                "/admin/runtime-nodes/drain",
                null,
                req,
                adminToken,
                new ParameterizedTypeReference<>() {
                }
        );
        if (res == null || res.getCode() == null || res.getCode() != 200) {
            return Result.error(res == null ? "deploy-proxy 请求失败" : res.getMessage());
        }
        return res;
    }

    private AdminDeployRuntimeNodeSummary toSummary(Map<String, Object> m) {
        AdminDeployRuntimeNodeSummary s = new AdminDeployRuntimeNodeSummary();
        if (m == null) return s;
        try {
            Object v = m.get("nodeId");
            if (v instanceof Number n) s.setNodeId(n.longValue());
        } catch (Exception ignore) {
        }
        s.setName(m.get("name") == null ? null : String.valueOf(m.get("name")));
        s.setAgentBaseUrl(m.get("agentBaseUrl") == null ? null : String.valueOf(m.get("agentBaseUrl")));
        s.setGatewayBaseUrl(m.get("gatewayBaseUrl") == null ? null : String.valueOf(m.get("gatewayBaseUrl")));
        try {
            Object v = m.get("enabled");
            if (v instanceof Boolean b) s.setEnabled(b);
            else if (v instanceof Number n) s.setEnabled(n.intValue() == 1);
            else if (v != null) s.setEnabled(Boolean.parseBoolean(String.valueOf(v)));
        } catch (Exception ignore) {
        }
        try {
            Object v = m.get("weight");
            if (v instanceof Number n) s.setWeight(n.intValue());
        } catch (Exception ignore) {
        }
        s.setHealth(m.get("health") == null ? null : String.valueOf(m.get("health")));

        // lastHeartbeatAt: deploy returns ISO Instant string; convert to ms for frontend
        try {
            Object v = m.get("lastHeartbeatAt");
            if (v != null) {
                Instant at = Instant.parse(String.valueOf(v));
                s.setLastHeartbeatAtMs(at.toEpochMilli());
            }
        } catch (Exception ignore) {
        }
        return s;
    }

    private AdminDeployRuntimePlacementsResponse toPlacements(Map<String, Object> m) {
        AdminDeployRuntimePlacementsResponse resp = new AdminDeployRuntimePlacementsResponse();
        if (m == null) return resp;
        try {
            Object v = m.get("nodeId");
            if (v instanceof Number n) resp.setNodeId(n.longValue());
        } catch (Exception ignore) {
        }
        try {
            Object v = m.get("total");
            if (v instanceof Number n) resp.setTotal(n.longValue());
        } catch (Exception ignore) {
        }
        try {
            Object items = m.get("items");
            if (items instanceof List<?> list) {
                List<AdminDeployRuntimePlacementItem> out = list.stream().map(it -> {
                    AdminDeployRuntimePlacementItem x = new AdminDeployRuntimePlacementItem();
                    if (it instanceof Map<?, ?> mm) {
                        Object appId = mm.get("appId");
                        if (appId instanceof Number n) x.setAppId(n.longValue());
                        Object nodeId = mm.get("nodeId");
                        if (nodeId instanceof Number n) x.setNodeId(n.longValue());
                        Object lastActiveAt = mm.get("lastActiveAt");
                        x.setLastActiveAt(lastActiveAt == null ? null : String.valueOf(lastActiveAt));
                    }
                    return x;
                }).collect(Collectors.toList());
                resp.setItems(out);
            }
        } catch (Exception ignore) {
        }
        return resp;
    }

    private AdminDeployRunnerSummary toRunnerSummary(Map<String, Object> m) {
        AdminDeployRunnerSummary s = new AdminDeployRunnerSummary();
        if (m == null) return s;
        s.setRunnerId(m.get("runnerId") == null ? null : String.valueOf(m.get("runnerId")));
        try {
            Object v = m.get("lastSeenAtMs");
            if (v instanceof Number n) s.setLastSeenAtMs(n.longValue());
            else if (v != null) s.setLastSeenAtMs(Long.parseLong(String.valueOf(v)));
        } catch (Exception ignore) {
        }
        s.setHealth(m.get("health") == null ? null : String.valueOf(m.get("health")));
        return s;
    }
}


