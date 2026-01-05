-- fun_ai_workspace_run：workspace 容器 + 运行态 last-known 记录（单机版）
-- 说明：
-- - user_id 唯一：一用户一容器
-- - 状态以运行时探测为准；DB 仅记录最后一次观测/操作结果

CREATE TABLE IF NOT EXISTS `fun_ai_workspace_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `app_id` BIGINT NULL,
  `container_name` VARCHAR(128) NULL,
  `host_port` INT NULL,
  `container_port` INT NULL,
  `container_status` VARCHAR(32) NULL,
  `run_state` VARCHAR(32) NULL,
  `run_pid` BIGINT NULL,
  `preview_url` VARCHAR(512) NULL,
  `log_path` VARCHAR(255) NULL,
  `last_error` VARCHAR(1024) NULL,
  `last_started_at` BIGINT NULL,
  `last_active_at` BIGINT NULL,
  `create_time` DATETIME NULL,
  `update_time` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


