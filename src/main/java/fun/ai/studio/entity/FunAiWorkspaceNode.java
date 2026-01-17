package fun.ai.studio.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("fun_ai_workspace_node")
public class FunAiWorkspaceNode {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("nginx_base_url")
    private String nginxBaseUrl;

    @TableField("api_base_url")
    private String apiBaseUrl;

    @TableField("enabled")
    private Integer enabled;

    @TableField("weight")
    private Integer weight;

    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}


