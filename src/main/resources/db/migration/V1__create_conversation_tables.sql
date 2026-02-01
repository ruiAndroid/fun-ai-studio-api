-- 创建 AI 对话会话表
CREATE TABLE IF NOT EXISTS `fun_ai_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `app_id` BIGINT NOT NULL COMMENT '应用ID',
    `title` VARCHAR(255) NOT NULL DEFAULT '新会话' COMMENT '会话标题',
    `message_count` INT NOT NULL DEFAULT 0 COMMENT '消息数量',
    `last_message_time` DATETIME NOT NULL COMMENT '最后一条消息时间',
    `archived` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已归档',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_app` (`user_id`, `app_id`),
    INDEX `idx_app_archived_time` (`app_id`, `archived`, `last_message_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话表';

-- 创建 AI 对话消息表
CREATE TABLE IF NOT EXISTS `fun_ai_conversation_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT NOT NULL COMMENT '会话ID',
    `role` VARCHAR(20) NOT NULL COMMENT '消息角色: user/assistant/system',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `sequence` INT NOT NULL COMMENT '消息序号',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_conversation_sequence` (`conversation_id`, `sequence`),
    CONSTRAINT `fk_message_conversation` FOREIGN KEY (`conversation_id`) 
        REFERENCES `fun_ai_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息表';
