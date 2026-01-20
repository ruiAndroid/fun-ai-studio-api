package fun.ai.studio.entity.request;

import lombok.Data;

/**
 * 批量 drain：把 sourceNodeId 下的 placements 迁到 targetNodeId。
 */
@Data
public class AdminWorkspaceDrainNodeRequest {
    private Long sourceNodeId;
    private Long targetNodeId;
    /**
     * 限制本次最多迁移多少个（避免一次性更新过多）。默认 100。
     */
    private Integer limit;
}


