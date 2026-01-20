package fun.ai.studio.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.entity.FunAiWorkspacePlacement;
import fun.ai.studio.entity.request.AdminUpsertWorkspaceNodeRequest;
import fun.ai.studio.entity.request.AdminWorkspaceDrainNodeRequest;
import fun.ai.studio.entity.request.AdminWorkspaceReassignPlacementRequest;
import fun.ai.studio.entity.request.WorkspaceNodeHeartbeatRequest;
import fun.ai.studio.entity.response.AdminWorkspaceNodeListResponse;
import fun.ai.studio.entity.response.AdminWorkspaceNodePlacementItem;
import fun.ai.studio.entity.response.AdminWorkspaceNodePlacementsResponse;
import fun.ai.studio.entity.response.AdminWorkspaceNodeSummary;
import fun.ai.studio.config.WorkspaceNodeRegistryProperties;
import fun.ai.studio.mapper.FunAiUserMapper;
import fun.ai.studio.mapper.FunAiWorkspaceNodeMapper;
import fun.ai.studio.mapper.FunAiWorkspacePlacementMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.time.ZoneId;
import java.time.Duration;

/**
 * 管理接口：workspace-node 节点管理（IP 白名单 + X-Admin-Token）
 */
@RestController
@RequestMapping("/api/fun-ai/admin/workspace-nodes")
@Tag(
        name = "Fun AI Workspace Nodes管理",
        description = "容器开发环境服务器节点管理（运维控制面，不依赖 JWT）。\n\n"
                + "访问入口：\n"
                + "- http://{{公网ip}}/admin/nodes.html#token={{adminToken}}\n"
                + "- http://{{公网ip}}/admin/nodes-admin.html?mode=workspace#token={{adminToken}}\n\n"
                + "鉴权方式：\n"
                + "- Header：X-Admin-Token={{adminToken}}\n"
                + "- 来源 IP：需在 funai.admin.allowed-ips 白名单内"
)
public class AdminWorkspaceNodeController {

    private final FunAiWorkspaceNodeMapper nodeMapper;
    private final FunAiWorkspacePlacementMapper placementMapper;
    private final FunAiUserMapper userMapper;
    private final WorkspaceNodeRegistryProperties registryProps;

    public AdminWorkspaceNodeController(FunAiWorkspaceNodeMapper nodeMapper, FunAiWorkspacePlacementMapper placementMapper, FunAiUserMapper userMapper, WorkspaceNodeRegistryProperties registryProps) {
        this.nodeMapper = nodeMapper;
        this.placementMapper = placementMapper;
        this.userMapper = userMapper;
        this.registryProps = registryProps;
    }

    @GetMapping("/list")
    @Operation(summary = "节点列表（含统计）")
    public Result<AdminWorkspaceNodeListResponse> list() {
        List<FunAiWorkspaceNode> nodes = nodeMapper.selectList(new QueryWrapper<>());
        nodes = (nodes == null ? List.<FunAiWorkspaceNode>of() : nodes).stream()
                .sorted(Comparator.comparing(FunAiWorkspaceNode::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        // 简单统计：count placements per nodeId（数据量不大时可接受；后续可做 group by SQL 优化）
        List<FunAiWorkspacePlacement> ps = placementMapper.selectList(new QueryWrapper<>());
        Map<Long, Long> cnt = new HashMap<>();
        if (ps != null) {
            for (FunAiWorkspacePlacement p : ps) {
                if (p == null || p.getNodeId() == null) continue;
                cnt.put(p.getNodeId(), cnt.getOrDefault(p.getNodeId(), 0L) + 1L);
            }
        }

        List<AdminWorkspaceNodeSummary> out = new ArrayList<>();
        for (FunAiWorkspaceNode n : nodes) {
            if (n == null) continue;
            AdminWorkspaceNodeSummary s = new AdminWorkspaceNodeSummary();
            s.setNodeId(n.getId());
            s.setName(n.getName());
            s.setNginxBaseUrl(n.getNginxBaseUrl());
            s.setApiBaseUrl(n.getApiBaseUrl());
            s.setEnabled(n.getEnabled());
            s.setWeight(n.getWeight());
            s.setAssignedUsers(cnt.getOrDefault(n.getId(), 0L));
            // lastHeartbeatAt
            if (n.getLastHeartbeatAt() != null) {
                try {
                    long ms = n.getLastHeartbeatAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    s.setLastHeartbeatAtMs(ms);
                } catch (Exception ignore) {
                    s.setLastHeartbeatAtMs(null);
                }
            }
            // health
            s.setHealth(computeHealth(n));
            out.add(s);
        }

        Long totalUsers = 0L;
        try {
            totalUsers = userMapper == null ? 0L : userMapper.selectCount(new QueryWrapper<>());
        } catch (Exception ignore) {
        }

        AdminWorkspaceNodeListResponse resp = new AdminWorkspaceNodeListResponse();
        resp.setTotalUsers(totalUsers);
        resp.setTotalPlacements(ps == null ? 0L : (long) ps.size());
        resp.setNodes(out);
        return Result.success(resp);
    }

    private String computeHealth(FunAiWorkspaceNode n) {
        if (n == null) return "UNKNOWN";
        if (registryProps == null || !registryProps.isEnabled()) return "UNKNOWN";
        if (n.getLastHeartbeatAt() == null) return "STALE";
        Duration stale = registryProps.heartbeatStaleDuration();
        try {
            LocalDateTime now = LocalDateTime.now();
            return n.getLastHeartbeatAt().isAfter(now.minusSeconds(stale.toSeconds())) ? "HEALTHY" : "STALE";
        } catch (Exception ignore) {
            return "UNKNOWN";
        }
    }

    @PostMapping("/upsert")
    @Operation(summary = "新增/更新节点")
    public Result<FunAiWorkspaceNode> upsert(@RequestBody AdminUpsertWorkspaceNodeRequest req) {
        if (req == null) return Result.error("body 不能为空");
        if (!StringUtils.hasText(req.getName())) return Result.error("name 不能为空");
        if (!StringUtils.hasText(req.getNginxBaseUrl())) return Result.error("nginxBaseUrl 不能为空");
        if (!StringUtils.hasText(req.getApiBaseUrl())) return Result.error("apiBaseUrl 不能为空");

        FunAiWorkspaceNode n = new FunAiWorkspaceNode();
        n.setId(req.getId());
        n.setName(req.getName().trim());
        n.setNginxBaseUrl(req.getNginxBaseUrl().trim());
        n.setApiBaseUrl(req.getApiBaseUrl().trim());
        n.setEnabled(req.getEnabled() == null ? 0 : req.getEnabled());
        n.setWeight(req.getWeight() == null ? 100 : req.getWeight());

        if (n.getId() == null) {
            nodeMapper.insert(n);
        } else {
            nodeMapper.updateById(n);
        }
        return Result.success(nodeMapper.selectById(n.getId()));
    }

    @PostMapping("/set-enabled")
    @Operation(summary = "启用/禁用节点")
    public Result<String> setEnabled(@RequestParam Long nodeId, @RequestParam Integer enabled) {
        if (nodeId == null) return Result.error("nodeId 不能为空");
        FunAiWorkspaceNode n = nodeMapper.selectById(nodeId);
        if (n == null) return Result.error("node 不存在");
        n.setEnabled(enabled == null ? 0 : enabled);
        nodeMapper.updateById(n);
        return Result.success("ok");
    }

    @PostMapping("/heartbeat")
    @Operation(summary = "workspace-node 心跳上报（内部）", description = "由 workspace-node 定时上报。鉴权：X-WS-Node-Token（独立于 X-Admin-Token）。")
    public Result<FunAiWorkspaceNode> heartbeat(@Valid @RequestBody WorkspaceNodeHeartbeatRequest req) {
        if (req == null) return Result.error("body 不能为空");

        String name = req.getNodeName().trim();
        QueryWrapper<FunAiWorkspaceNode> qw = new QueryWrapper<>();
        qw.eq("name", name).last("limit 1");
        FunAiWorkspaceNode existing = nodeMapper.selectOne(qw);

        FunAiWorkspaceNode n = (existing != null) ? existing : new FunAiWorkspaceNode();
        n.setName(name);
        n.setNginxBaseUrl(req.getNginxBaseUrl().trim());
        n.setApiBaseUrl(req.getApiBaseUrl().trim());
        if (n.getEnabled() == null) n.setEnabled(1);
        if (n.getWeight() == null) n.setWeight(100);
        n.setLastHeartbeatAt(LocalDateTime.now());

        if (n.getId() == null) {
            nodeMapper.insert(n);
        } else {
            nodeMapper.updateById(n);
        }

        return Result.success(nodeMapper.selectById(n.getId()));
    }

    @GetMapping("/placements")
    @Operation(summary = "查询某节点的 placements（userId -> nodeId）", description = "用于人工迁移/drain 前查看绑定用户列表。")
    public Result<AdminWorkspaceNodePlacementsResponse> placements(
            @RequestParam Long nodeId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "200") long limit
    ) {
        if (nodeId == null) return Result.error("nodeId 不能为空");
        long safeLimit = Math.min(Math.max(limit, 1), 2000);
        long safeOffset = Math.max(offset, 0);

        QueryWrapper<FunAiWorkspacePlacement> qw = new QueryWrapper<>();
        qw.eq("node_id", nodeId)
                .orderByAsc("id")
                .last("limit " + safeOffset + "," + safeLimit);
        List<FunAiWorkspacePlacement> ps = placementMapper.selectList(qw);

        List<AdminWorkspaceNodePlacementItem> items = (ps == null ? List.<FunAiWorkspacePlacement>of() : ps).stream()
                .filter(p -> p != null && p.getUserId() != null)
                .map(p -> {
                    AdminWorkspaceNodePlacementItem it = new AdminWorkspaceNodePlacementItem();
                    it.setUserId(p.getUserId());
                    it.setNodeId(p.getNodeId());
                    it.setLastActiveAt(p.getLastActiveAt());
                    return it;
                })
                .collect(Collectors.toList());

        Long total = 0L;
        try {
            QueryWrapper<FunAiWorkspacePlacement> cqw = new QueryWrapper<>();
            cqw.eq("node_id", nodeId);
            total = placementMapper.selectCount(cqw);
        } catch (Exception ignore) {
        }

        AdminWorkspaceNodePlacementsResponse resp = new AdminWorkspaceNodePlacementsResponse();
        resp.setNodeId(nodeId);
        resp.setTotal(total);
        resp.setItems(items);
        return Result.success(resp);
    }

    @PostMapping("/reassign")
    @Operation(summary = "手工迁移某用户 placement 到指定节点", description = "仅修改 userId->nodeId 绑定关系；是否触发容器重建由业务侧后续处理。")
    public Result<String> reassign(@RequestBody AdminWorkspaceReassignPlacementRequest req) {
        if (req == null) return Result.error("body 不能为空");
        if (req.getUserId() == null) return Result.error("userId 不能为空");
        if (req.getTargetNodeId() == null) return Result.error("targetNodeId 不能为空");

        FunAiWorkspaceNode target = nodeMapper.selectById(req.getTargetNodeId());
        if (target == null) return Result.error("target node 不存在");
        if (target.getEnabled() == null || target.getEnabled() != 1) return Result.error("target node 未启用");

        QueryWrapper<FunAiWorkspacePlacement> qw = new QueryWrapper<>();
        qw.eq("user_id", req.getUserId()).last("limit 1");
        FunAiWorkspacePlacement p = placementMapper.selectOne(qw);
        if (p == null) {
            p = new FunAiWorkspacePlacement();
            p.setUserId(req.getUserId());
            p.setNodeId(req.getTargetNodeId());
            placementMapper.insert(p);
            return Result.success("created");
        }
        p.setNodeId(req.getTargetNodeId());
        placementMapper.updateById(p);
        return Result.success("ok");
    }

    @PostMapping("/drain")
    @Operation(summary = "批量 drain：将 sourceNodeId 下的 placements 迁移到 targetNodeId", description = "默认最多迁移 100 条；可重复调用分批完成。")
    public Result<Map<String, Object>> drain(@RequestBody AdminWorkspaceDrainNodeRequest req) {
        if (req == null) return Result.error("body 不能为空");
        if (req.getSourceNodeId() == null) return Result.error("sourceNodeId 不能为空");
        if (req.getTargetNodeId() == null) return Result.error("targetNodeId 不能为空");
        if (req.getSourceNodeId().equals(req.getTargetNodeId())) return Result.error("source/target 不能相同");

        FunAiWorkspaceNode target = nodeMapper.selectById(req.getTargetNodeId());
        if (target == null) return Result.error("target node 不存在");
        if (target.getEnabled() == null || target.getEnabled() != 1) return Result.error("target node 未启用");

        int limit = req.getLimit() == null ? 100 : req.getLimit();
        int safeLimit = Math.min(Math.max(limit, 1), 2000);

        QueryWrapper<FunAiWorkspacePlacement> qw = new QueryWrapper<>();
        qw.eq("node_id", req.getSourceNodeId())
                .orderByAsc("id")
                .last("limit " + safeLimit);
        List<FunAiWorkspacePlacement> ps = placementMapper.selectList(qw);

        int moved = 0;
        if (ps != null) {
            for (FunAiWorkspacePlacement p : ps) {
                if (p == null || p.getId() == null) continue;
                p.setNodeId(req.getTargetNodeId());
                placementMapper.updateById(p);
                moved++;
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("moved", moved);
        out.put("sourceNodeId", req.getSourceNodeId());
        out.put("targetNodeId", req.getTargetNodeId());
        out.put("limit", safeLimit);
        return Result.success(out);
    }
}


