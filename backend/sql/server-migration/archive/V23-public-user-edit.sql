-- V23：后台用户编辑权限及主站登录凭据版本。
SET @ddl := IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND COLUMN_NAME = 'auth_version'),
    'SELECT 1', 'ALTER TABLE public_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT ''主站登录凭据版本''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order)
VALUES ('user:edit', '编辑用户资料', '用户', 'BUTTON', 75);
