-- ============================================================
-- V14 迁移：数据保留策略（审计日志 / SQL 监控仅保留最近 N 天）
-- - 保留期限存 site_settings 的 retention 组（默认 7，可选 7/15/30/90），后台「数据保留」页可改；
-- - 权限码 retention:view / retention:edit / retention:run（仅 SUPER_ADMIN，不下发 EDITOR）；
-- - 执行周期为部署级参数 app.retention.cron（application.yml，默认每日 03:30），不入库。
-- 针对已初始化数据库的增量升级；可重复执行（INSERT IGNORE）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v14-data-retention.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 保留策略默认值（7 天，启用）。已存在则不覆盖，避免冲掉后台已改的配置。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('retention', 'retention.days',    '7',
 '数据保留 · 审计日志/SQL监控保留天数（可选 7/15/30/90）', CURRENT_TIMESTAMP),
('retention', 'retention.enabled', 'true',
 '数据保留 · 是否启用定时自动清理（true/false）', CURRENT_TIMESTAMP),
('retention', 'retention.sql_monitor_last_reset', '',
 '数据保留 · 上次 SQL 监控统计重置时间（系统写入，勿手改）', CURRENT_TIMESTAMP),
('retention', 'retention.last_run_at', '',
 '数据保留 · 上次清理执行时间（系统写入，勿手改）', CURRENT_TIMESTAMP),
('retention', 'retention.last_run_trigger', '',
 '数据保留 · 上次清理触发方式（scheduled/manual，系统写入）', CURRENT_TIMESTAMP),
('retention', 'retention.last_run_deleted', '',
 '数据保留 · 上次清理删除的审计日志条数（系统写入）', CURRENT_TIMESTAMP);

-- 2. 数据保留权限码（页面 /admin/retention）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('retention:view', '数据保留',     '数据保留', 'MENU',   108),
('retention:edit', '编辑保留策略', '数据保留', 'BUTTON', 109),
('retention:run',  '立即执行清理', '数据保留', 'BUTTON', 110);

-- 3. 属系统级破坏性操作（物理删除），仅 SUPER_ADMIN（代码内加载全部权限）可用；
--    不下发给 EDITOR，故此处无 sys_role_permission 写入。
