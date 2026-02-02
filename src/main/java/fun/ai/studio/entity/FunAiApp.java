package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI应用实体类
 */
@Data
@TableName("fun_ai_app")
public class FunAiApp {
    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "应用ID")
    private Long id;

    /**
     * 用户ID（外键）
     */
    @TableField("user_id")
    @Schema(description = "用户ID")
    private Long userId;

    /**
     * 应用名称
     */
    @TableField("app_name")
    @Schema(description = "应用名称")
    private String appName;

    /**
     * 应用描述
     */
    @TableField("app_description")
    @Schema(description = "应用描述")
    private String appDescription;

    /**
     * 应用类型（如小程序、网站等）
     */
    @TableField("app_type")
    @Schema(description = "应用类型")
    private String appType;

    @TableField("app_status")
    @Schema(description = "应用状态（0=空壳/草稿；1=已上传；2=部署中；3=可访问；4=部署失败；5=禁用）")
    private Integer appStatus;

    /**
     * 最近一次部署失败原因（用于前端轮询时展示错误）
     */
    @TableField("last_deploy_error")
    @Schema(description = "最近一次部署失败原因（为空表示无错误）")
    private String lastDeployError;

    @TableField(exist = false)
    @Schema(description = "（部署态）可访问地址（由 Deploy/Runtime 计算得到）；开发态预览请用 workspacePreviewUrl", example = "http://172.21.138.102/runtime/20000254/")
    private String accessUrl;

    @TableField(exist = false)
    @Schema(description = "（部署态）可访问地址（推荐前端使用该字段；与开发态 workspacePreviewUrl 分开）", example = "http://172.21.138.102/runtime/20000254/")
    private String deployAccessUrl;

    // ----------------------
    // Workspace/容器运行时视图（last-known，不落库到 fun_ai_app）
    // ----------------------

    @TableField(exist = false)
    @Schema(description = "（运行时）容器状态：NOT_CREATED/RUNNING/EXITED/UNKNOWN（来自 fun_ai_workspace_run）")
    private String workspaceContainerStatus;

    @TableField(exist = false)
    @Schema(description = "（运行时）该应用在容器中的运行态：IDLE/STARTING/RUNNING/DEAD/UNKNOWN（仅当当前运行 appId==本应用 id 时有值）")
    private String workspaceRunState;

    @TableField(exist = false)
    @Schema(description = "（运行时）预览地址（仅当 workspaceRunState=RUNNING 时有值）")
    private String workspacePreviewUrl;

    @TableField(exist = false)
    @Schema(description = "（运行时）日志路径（例如 /workspace/run/dev.log）")
    private String workspaceLogPath;

    @TableField(exist = false)
    @Schema(description = "（运行时）最近一次错误/提示信息（用于排查/展示）")
    private String workspaceLastError;

    @TableField(exist = false)
    @Schema(description = "（运行时）workspace 中是否存在该 app 的项目目录（/workspace/apps/{appId}）")
    private Boolean workspaceHasProjectDir;

    @TableField(exist = false)
    @Schema(description = "（运行时）workspace 项目目录下是否能检测到 package.json（maxDepth=2）")
    private Boolean workspaceHasPackageJson;

    /**
     * 应用密钥
     */
    @TableField("app_key")
    @Schema(description = "应用密钥")
    private String appKey;

    /**
     * 应用密钥
     */
    @TableField("app_secret")
    @Schema(description = "应用密钥")
    @JsonIgnore
    private String appSecret;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonIgnore
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    @JsonIgnore
    private LocalDateTime updateTime;

}