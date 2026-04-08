-- 为 fun_ai_user 表添加用户类型字段
ALTER TABLE `fun_ai_user` ADD COLUMN `user_type` TINYINT NOT NULL DEFAULT 0 COMMENT '用户类型: 0-普通用户, 1-管理员用户';
