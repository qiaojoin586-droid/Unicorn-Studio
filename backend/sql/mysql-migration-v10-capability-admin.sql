-- ============================================================
-- V10 迁移：我们培养的能力（首页新区块 + 后台管理）
-- 针对已初始化数据库的增量升级。
-- 幂等：建表用 IF NOT EXISTS，权限码与角色映射可重复执行。
-- 执行方式：mysql -u root -p unicorn_studio < mysql-migration-v10-capability-admin.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 培养能力条目表（结构对齐 service_item）
CREATE TABLE IF NOT EXISTS capability_item (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  icon        VARCHAR(50),
  sort_order  INT NOT NULL DEFAULT 0,
  status      VARCHAR(20) NOT NULL DEFAULT 'published' COMMENT 'published/offline',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. 默认内容（仅当表为空时插入，避免重复执行产生冗余数据）
INSERT INTO capability_item (title, description, icon, sort_order, status, created_at, updated_at)
SELECT * FROM (
  SELECT '工程实践能力' AS title, '从需求分析到上线部署的完整工程链路训练，形成可复用的开发规范与协作习惯。' AS description, '🛠️' AS icon, 1 AS sort_order, 'published' AS status, CURRENT_TIMESTAMP AS created_at, CURRENT_TIMESTAMP AS updated_at
  UNION ALL SELECT '学术研究能力', '文献调研、实验设计与论文写作的系统训练，具备独立开展前沿课题研究的能力。', '📚', 2, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  UNION ALL SELECT '团队协作能力', '通过真实项目与竞赛锤炼沟通、分工与协作能力，在团队中高效推进目标落地。', '🤝', 3, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM capability_item);

-- 3. 培养能力权限码
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('capability:list',   '培养能力列表', '我们培养的能力', 'MENU', 100),
('capability:create', '新增培养能力', '我们培养的能力', 'BUTTON', 101),
('capability:edit',   '编辑培养能力', '我们培养的能力', 'BUTTON', 102),
('capability:delete', '删除培养能力', '我们培养的能力', 'BUTTON', 103);

-- 4. 内容小编默认获得培养能力管理权限；SUPER_ADMIN 会在代码中加载全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'capability:list','capability:create','capability:edit','capability:delete'
);

-- 5. 仅更新未被人工改写的内置角色说明
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力管理'
WHERE code = 'EDITOR'
  AND builtin = 1
  AND description = '仪表盘 + 作品/新闻/轮播/招收方向管理';
