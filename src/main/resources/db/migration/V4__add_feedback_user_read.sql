-- 用户反馈表新增用户已读标记字段
ALTER TABLE `fun_ai_feedback` ADD COLUMN `user_read` TINYINT NOT NULL DEFAULT 0 COMMENT '用户是否已读回复：0-未读，1-已读';
