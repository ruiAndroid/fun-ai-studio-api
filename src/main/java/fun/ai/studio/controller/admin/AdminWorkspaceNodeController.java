package fun.ai.studio.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import fun.ai.studio.common.Result;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.entity.FunAiWorkspacePlacement;
import fun.ai.studio.entity.request.AdminUpsertWorkspaceNodeRequest;
import fun.ai.studio.entity.response.AdminWorkspaceNodeListResponse;
import fun.ai.studio.entity.response.AdminWorkspaceNodeSummary;
import fun.ai.studio.mapper.FunAiUserMapper;
import fun.ai.studio.mapper.FunAiWorkspaceNodeMapper;
import fun.ai.studio.mapper.FunAiWorkspacePlacementMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public AdminWorkspaceNodeController(FunAiWorkspaceNodeMapper nodeMapper, FunAiWorkspacePlacementMapper placementMapper, FunAiUserMapper userMapper) {
        this.nodeMapper = nodeMapper;
        this.placementMapper = placementMapper;
        this.userMapper = userMapper;
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
}


