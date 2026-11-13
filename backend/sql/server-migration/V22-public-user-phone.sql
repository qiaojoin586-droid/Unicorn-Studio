-- V22：主站手机号注册和登录，保留旧账号邮箱和用户 ID。
SET @ddl := IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND COLUMN_NAME = 'phone'),
    'SELECT 1', 'ALTER TABLE public_user ADD COLUMN phone VARCHAR(11) NULL COMMENT ''主站登录手机号，旧账号待绑定''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME = 'uk_public_user_phone'),
    'SELECT 1', 'ALTER TABLE public_user ADD UNIQUE INDEX uk_public_user_phone (phone)');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order)
VALUES ('user:bind-phone', '绑定旧账号手机号', '用户', 'BUTTON', 74);
