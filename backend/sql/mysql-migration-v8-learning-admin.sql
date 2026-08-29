-- ============================================================
-- V8 迁移：后台学习资料管理模块（针对已初始化数据库的增量升级）
-- 向后兼容：仅新增列/新表/新权限码，不修改、不删除任何现有表结构与数据。
-- 幂等：可重复执行（列存在则跳过；权限码/映射重复则忽略）。
-- 执行方式：mysql -u root -p unicorn_studio < mysql-migration-v8-learning-admin.sql
-- ============================================================

-- 1. learning_resources 补列（tags / attachment_url），存在则跳过
SET @add_tags := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_resources' AND COLUMN_NAME = 'tags'),
    'SELECT 1',
    'ALTER TABLE learning_resources ADD COLUMN tags VARCHAR(255) NULL COMMENT ''逗号分隔标签'' AFTER category'
  )
);
PREPARE stmt FROM @add_tags; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @add_attachment := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_resources' AND COLUMN_NAME = 'attachment_url'),
    'SELECT 1',
    'ALTER TABLE learning_resources ADD COLUMN attachment_url VARCHAR(255) NULL COMMENT ''附件（上传文件 URL）'' AFTER cover_url'
  )
);
PREPARE stmt FROM @add_attachment; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 学习资料分类字典表（新表，不影响现有表）
CREATE TABLE IF NOT EXISTS learning_category (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(50) NOT NULL UNIQUE,
  description VARCHAR(255),
  sort_order  INT NOT NULL DEFAULT 0,
  status      INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. 权限码（code 唯一，INSERT IGNORE 幂等）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('learning:list',     '学习资料列表', '学习资料', 'MENU', 94),
('learning:create',   '新增学习资料', '学习资料', 'BUTTON', 95),
('learning:edit',     '编辑学习资料', '学习资料', 'BUTTON', 96),
('learning:delete',   '删除学习资料', '学习资料', 'BUTTON', 97),
('learning:publish',  '上下架资料',   '学习资料', 'BUTTON', 98),
('learning:category', '资料分类管理', '学习资料', 'MENU', 99);

-- 4. 内容小编（EDITOR）同步获得学习资料管理权限（复合主键去重）
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'learning:list','learning:create','learning:edit','learning:delete','learning:publish','learning:category'
);

-- 5. 预置常用分类（name 唯一，INSERT IGNORE 幂等）
INSERT IGNORE INTO learning_category (name, description, sort_order, status) VALUES
('算法竞赛', 'ACM/ICPC、蓝桥杯等竞赛资料', 1, 1),
('工程开发', '前后端与工程实践教程', 2, 1),
('设计',     'UI/视觉设计学习资料', 3, 1),
('其他',     '未归类学习资料', 99, 1);
