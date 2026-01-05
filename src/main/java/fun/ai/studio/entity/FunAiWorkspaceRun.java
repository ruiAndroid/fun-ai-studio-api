package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单机版：workspace 容器 + 运行态的“最后一次观测/最后一次操作”落库（last-known）。
 *
 * 注意：
 * - 运行时真相仍以 docker/端口/进程探测为准
 * - DB 主要用于：展示/审计/服务重启后的“最后状态”
 */
@Data
@TableName("fun_ai_workspace_run")
public class FunAiWorkspaceRun {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    @Schema(description = "用户ID（单机版：唯一）")
    private Long userId;

    @TableField("app_id")
    @Schema(description = "当前运行/最近一次运行的应用ID（可为空）")
    private Long appId;

    @TableField("container_name")
    @Schema(description = "容器名（ws-u-{userId}）")
    private String containerName;

    @TableField("host_port")
    @Schema(description = "宿主机端口（20000+）")
    private Integer hostPort;

    @TableField("container_port")
    @Schema(description = "容器端口（默认 5173）")
    private Integer containerPort;

    @TableField("container_status")
    @Schema(description = "容器状态：NOT_CREATED/RUNNING/EXITED/UNKNOWN")
    private String containerStatus;

    @TableField("run_state")
    @Schema(description = "运行态：IDLE/STARTING/RUNNING/DEAD/UNKNOWN")
    private String runState;

    @TableField("run_pid")
    @Schema(description = "dev 进程 pid（容器内）")
    private Long runPid;

    @TableField("preview_url")
    @Schema(description = "RUNNING 时可访问的预览地址")
    private String previewUrl;

    @TableField("log_path")
    @Schema(description = "容器内日志路径（例如 /workspace/run/dev.log）")
    private String logPath;

    @TableField("last_error")
    @Schema(description = "最近一次错误/提示信息（用于排查）")
    private String lastError;

    @TableField("last_started_at")
    @Schema(description = "最近一次启动时间（epoch seconds，可为空）")
    private Long lastStartedAt;

    @TableField("last_active_at")
    @Schema(description = "最近一次活跃时间（epoch ms，可为空；建议由 heartbeat 更新）")
    private Long lastActiveAt;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}


