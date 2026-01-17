package fun.ai.studio.service;

import com.baomidou.mybatisplus.extension.service.IService;
import fun.ai.studio.entity.FunAiWorkspaceNode;
import fun.ai.studio.entity.FunAiWorkspacePlacement;

/**
 * userId -> workspace node 的粘性落点服务（控制面使用）
 */
public interface FunAiWorkspacePlacementService extends IService<FunAiWorkspacePlacement> {

    /**
     * 获取或分配用户落点（幂等）：如果 userId 还没分配，则从 enabled 节点中挑选并写入 placement。
     */
    FunAiWorkspacePlacement ensurePlacement(Long userId);

    /**
     * 解析 userId 的节点信息（包含 nginx/api baseUrl）
     */
    FunAiWorkspaceNode resolveNode(Long userId);
}


