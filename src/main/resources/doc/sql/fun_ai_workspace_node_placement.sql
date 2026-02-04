-- Workspace 节点与粘性落点（userId -> nodeId）
CREATE TABLE IF NOT EXISTS `fun_ai_workspace_node` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '节点ID',
    `name` VARCHAR(64) NOT NULL COMMENT '节点名（唯一）',
    `nginx_base_url` VARCHAR(255) NOT NULL COMMENT '节点 Nginx 基址（用于 /preview 路由）',
    `api_base_url` VARCHAR(255) NOT NULL COMMENT '节点 workspace-node API 基址',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
    `weight` INT NOT NULL DEFAULT 100 COMMENT '权重（预留）',
    `last_heartbeat_at` DATETIME NULL COMMENT '最近心跳时间',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    INDEX `idx_enabled` (`enabled`),
    INDEX `idx_last_heartbeat_at` (`last_heartbeat_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workspace 节点表';

CREATE TABLE IF NOT EXISTS `fun_ai_workspace_placement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `node_id` BIGINT NOT NULL COMMENT '节点ID',
    `last_active_at` BIGINT NOT NULL DEFAULT 0 COMMENT '用户最后活跃时间（毫秒）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    INDEX `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Workspace 用户落点表';

-- 示例（按需替换 URL）
-- INSERT INTO fun_ai_workspace_node (name, nginx_base_url, api_base_url, enabled, weight)
-- VALUES ('ws-node-01', 'http://172.21.138.87', 'http://172.21.138.87:7001', 1, 100);
