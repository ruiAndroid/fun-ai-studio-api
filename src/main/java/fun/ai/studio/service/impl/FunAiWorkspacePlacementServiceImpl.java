package fun.ai.studio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import fun.ai.studio.common.WorkspaceNodeProxyException;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.entity.FunAiWorkspacePlacement;
import fun.ai.studio.mapper.FunAiWorkspaceNodeMapper;
import fun.ai.studio.mapper.FunAiWorkspacePlacementMapper;
import fun.ai.studio.service.FunAiWorkspacePlacementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class FunAiWorkspacePlacementServiceImpl
        extends ServiceImpl<FunAiWorkspacePlacementMapper, FunAiWorkspacePlacement>
        implements FunAiWorkspacePlacementService {

    private final FunAiWorkspaceNodeMapper nodeMapper;

    public FunAiWorkspacePlacementServiceImpl(FunAiWorkspaceNodeMapper nodeMapper) {
        this.nodeMapper = nodeMapper;
    }

    @Override
    @Transactional
    public FunAiWorkspacePlacement ensurePlacement(Long userId) {
        if (userId == null) throw new IllegalArgumentException("userId 不能为空");

        FunAiWorkspacePlacement existing = getByUserId(userId);
        if (existing != null && existing.getNodeId() != null) {
            return existing;
        }

        FunAiWorkspaceNode chosen = chooseNodeForUser(userId);

        // 幂等：依赖 uk_user_id(user_id)，并发时若插入失败则回查一次
        FunAiWorkspacePlacement p = new FunAiWorkspacePlacement();
        p.setUserId(userId);
        p.setNodeId(chosen.getId());
        try {
            save(p);
            return p;
        } catch (Exception ignore) {
            FunAiWorkspacePlacement again = getByUserId(userId);
            if (again != null && again.getNodeId() != null) return again;
            throw new WorkspaceNodeProxyException("ensure placement failed: userId=" + userId);
        }
    }

    @Override
    public FunAiWorkspaceNode resolveNode(Long userId) {
        FunAiWorkspacePlacement placement = ensurePlacement(userId);
        Long nodeId = placement == null ? null : placement.getNodeId();
        if (nodeId == null) throw new WorkspaceNodeProxyException("placement nodeId is null: userId=" + userId);
        FunAiWorkspaceNode node = nodeMapper.selectById(nodeId);
        if (node == null || node.getEnabled() == null || node.getEnabled() != 1) {
            throw new WorkspaceNodeProxyException("workspace node disabled/not found: nodeId=" + nodeId);
        }
        if (!StringUtils.hasText(node.getApiBaseUrl()) || !StringUtils.hasText(node.getNginxBaseUrl())) {
            throw new WorkspaceNodeProxyException("workspace node baseUrl empty: nodeId=" + nodeId);
        }
        return node;
    }

    private FunAiWorkspacePlacement getByUserId(Long userId) {
        QueryWrapper<FunAiWorkspacePlacement> qw = new QueryWrapper<>();
        qw.eq("user_id", userId).last("limit 1");
        return getBaseMapper().selectOne(qw);
    }

    private FunAiWorkspaceNode chooseNodeForUser(Long userId) {
        // 策略：一致性哈希（稳定分配）+ 只选择 enabled 节点
        QueryWrapper<FunAiWorkspaceNode> qw = new QueryWrapper<>();
        qw.eq("enabled", 1);
        List<FunAiWorkspaceNode> nodes = nodeMapper.selectList(qw);
        if (nodes == null || nodes.isEmpty()) {
            throw new WorkspaceNodeProxyException("no enabled workspace nodes");
        }
        nodes = nodes.stream()
                .filter(Objects::nonNull)
                .filter(n -> n.getId() != null)
                .filter(n -> StringUtils.hasText(n.getApiBaseUrl()))
                .filter(n -> StringUtils.hasText(n.getNginxBaseUrl()))
                .sorted(Comparator.comparing(FunAiWorkspaceNode::getId))
                .toList();
        if (nodes.isEmpty()) throw new WorkspaceNodeProxyException("no valid workspace nodes");

        int idx = Math.floorMod(Long.hashCode(userId), nodes.size());
        return nodes.get(idx);
    }
}


