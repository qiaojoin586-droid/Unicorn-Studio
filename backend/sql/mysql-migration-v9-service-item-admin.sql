-- ============================================================
-- V9 迁移：主要招收方向后台管理（针对已初始化数据库的增量升级）
-- 向后兼容：service_item 表与公开接口保持不变，仅新增后台权限码、角色映射和默认说明。
-- 幂等：权限码和映射均可重复执行。
-- 执行方式：mysql -u root -p unicorn_studio < mysql-migration-v9-service-item-admin.sql
-- ============================================================

-- 1. 主要招收方向权限码
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('service:list',   '招收方向列表', '主要招收方向', 'MENU', 44),
('service:create', '新增招收方向', '主要招收方向', 'BUTTON', 45),
('service:edit',   '编辑招收方向', '主要招收方向', 'BUTTON', 46),
('service:delete', '删除招收方向', '主要招收方向', 'BUTTON', 47);

-- 2. 内容小编默认获得主要招收方向管理权限；SUPER_ADMIN 会在代码中加载全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'service:list','service:create','service:edit','service:delete'
);

-- 3. 仅更新未被人工改写的内置角色说明
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向管理'
WHERE code = 'EDITOR'
  AND builtin = 1
  AND description = '仪表盘 + 作品/新闻/轮播管理';
