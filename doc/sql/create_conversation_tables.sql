-- =====================================================
-- AI 对话上下文管理 - 数据库表创建脚本
-- =====================================================
-- 功能说明：
-- 1. 每个应用最多允许 5 个活跃会话（可通过配置调整）
-- 2. 每个会话最多允许 30 条消息（可通过配置调整）
-- 3. 支持会话归档功能
-- 4. 应用删除时自动清理所有会话
-- =====================================================

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

-- =====================================================
-- 使用说明：
-- =====================================================
-- 1. 在 application.properties 中配置：
--    funai.conversation.max-conversations-per-app=5
--    funai.conversation.max-messages-per-conversation=30
--
-- 2. API 端点：
--    POST   /api/fun-ai/conversation/create          - 创建会话
--    GET    /api/fun-ai/conversation/list            - 获取会话列表
--    GET    /api/fun-ai/conversation/detail          - 获取会话详情
--    POST   /api/fun-ai/conversation/message/add     - 添加消息
--    POST   /api/fun-ai/conversation/title           - 更新标题
--    GET    /api/fun-ai/conversation/delete          - 删除会话
--    POST   /api/fun-ai/conversation/rollback        - 回退到指定消息节点
--
-- 3. 回退功能说明：
--    - 回退到指定消息：保留该消息及之前的所有消息，删除之后的消息
--    - 用于撤销错误的对话或重新开始某个分支
--    - 会自动更新会话的消息数量和最后消息时间
--
-- 4. Swagger UI 访问：http://your-host:8080/swagger-ui
-- =====================================================
