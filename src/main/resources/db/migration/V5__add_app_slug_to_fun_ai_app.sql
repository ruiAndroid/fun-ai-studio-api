ALTER TABLE `fun_ai_app`
  ADD COLUMN `app_slug` VARCHAR(40) NULL COMMENT '公网访问别名（全平台唯一）';

CREATE UNIQUE INDEX `uk_fun_ai_app_app_slug`
  ON `fun_ai_app` (`app_slug`);
