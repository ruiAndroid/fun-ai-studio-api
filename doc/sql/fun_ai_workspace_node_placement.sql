-- fun_ai_workspace_node：workspace 节点表（4 台或更多 workspace-node）
-- fun_ai_workspace_placement：用户落点表（userId -> nodeId，粘性落点）
--
-- 设计目标：
-- - 支持多台 workspace-node 横向扩容
-- - 同一 userId 固定落到某一台 node（本地盘持久化策略）
-- - 网关/入口 Nginx 可根据 userId 查询 node 的 nginx_base_url 做动态转发
-- - API 的 workspace-node proxy 可根据 userId 查询 node 的 api_base_url 做签名转发

CREATE TABLE IF NOT EXISTS `fun_ai_workspace_node` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `nginx_base_url` VARCHAR(255) NOT NULL COMMENT '节点Nginx基址，例如 http://10.0.0.11',
  `api_base_url` VARCHAR(255) NOT NULL COMMENT '节点workspace-node基址，例如 http://10.0.0.11:7001',
  `enabled` TINYINT NOT NULL DEFAULT 1,
  `weight` INT NOT NULL DEFAULT 100,
  `last_heartbeat_at` DATETIME NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_name` (`name`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `fun_ai_workspace_placement` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `node_id` BIGINT NOT NULL,
  `last_active_at` BIGINT NULL COMMENT 'epoch ms，可选：用于多机idle回收/活跃记录',
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_node_id` (`node_id`),
  CONSTRAINT `fk_ws_placement_node` FOREIGN KEY (`node_id`) REFERENCES `fun_ai_workspace_node` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 初始化示例（按需替换为你的内网IP；可多次执行，重复 name 会因 uk_node_name 报错）
-- INSERT INTO fun_ai_workspace_node(name, nginx_base_url, api_base_url, enabled, weight)
-- VALUES
-- ('ws-node-01', 'http://10.0.0.11', 'http://10.0.0.11:7001', 1, 100),
-- ('ws-node-02', 'http://10.0.0.12', 'http://10.0.0.12:7001', 1, 100),
-- ('ws-node-03', 'http://10.0.0.13', 'http://10.0.0.13:7001', 1, 100),
-- ('ws-node-04', 'http://10.0.0.14', 'http://10.0.0.14:7001', 1, 100);


