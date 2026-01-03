package fun.ai.studio.entity.response;

import lombok.Data;

/**
 * workspace 运行态（dev/build/run）
 */
@Data
public class FunAiWorkspaceRunStatusResponse {
    private Long userId;
    /**
     * IDLE / RUNNING / DEAD
     */
    private String state;
    private Long appId;
    private Integer hostPort;
    private Integer containerPort;
    private Long pid;
    /**
     * 容器内日志路径（挂载后宿主机也可读）
     */
    private String logPath;
    /**
     * 提示信息（例如已在运行）
     */
    private String message;
}


