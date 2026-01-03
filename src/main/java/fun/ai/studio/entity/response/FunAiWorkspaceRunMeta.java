package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * 存储在 /workspace/run/current.json 的运行元数据
 */
@Data
public class FunAiWorkspaceRunMeta {
    private Long appId;
    private String type; // DEV
    private Long pid;
    private Long startedAt; // epoch seconds
    private String logPath;
}


