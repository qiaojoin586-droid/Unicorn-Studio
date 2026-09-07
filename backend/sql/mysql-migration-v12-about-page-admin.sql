-- ============================================================
-- V12 迁移：「关于我们」页面内容接入后台管理
-- 页面文案改由后台「关于我们」页面（/admin/about）维护，存储复用
-- site_settings 的 about 组（单例页面配置，不新建表）。
-- 针对已初始化数据库的增量升级；可重复执行（INSERT IGNORE / 带条件 UPDATE）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v12-about-page-admin.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 页面内容默认值 = 改造前原始文案。
--    写入后后台表单即为当前线上文案，编辑不会造成内容突变；
--    若不自带 about 组，后台读取也会回落到同样的默认值（见 AboutPageService），此处仅是让后台可见可改。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('about', 'about.eyebrow',   'About Us',
 '关于我们 · 顶部小标签', CURRENT_TIMESTAMP),
('about', 'about.title',     '关于独角兽工作室',
 '关于我们 · 主标题', CURRENT_TIMESTAMP),
('about', 'about.subtitle',  '一群热爱技术与创造力的伙伴，致力于在数字世界中创造价值',
 '关于我们 · 副标题', CURRENT_TIMESTAMP),
('about', 'about.content',   '独角兽工作室是一支充满激情的技术团队，专注于为客户提供高质量的数字产品与服务。我们相信，优秀的技术结合出色的设计，能够为用户带来非凡的体验。\n\n自创立以来，我们已为众多企业提供了 Web 开发、移动应用、大模型学术研究等方面的专业服务，赢得了客户的广泛认可与好评。',
 '关于我们 · 正文，段落间用空行分隔', CURRENT_TIMESTAMP),
('about', 'about.image_url', '',
 '关于我们 · 右侧配图（留空则使用站点默认 LOGO）', CURRENT_TIMESTAMP),
('about', 'about.stats',     '[{"value":"50+","label":"完成项目"},{"value":"30+","label":"合作客户"},{"value":"5+","label":"年行业经验"}]',
 '关于我们 · 统计指标（JSON 数组）', CURRENT_TIMESTAMP);

-- 2. 关于我们管理权限码（页面 /admin/about）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('about:view', '关于我们',     '关于我们', 'MENU',   104),
('about:edit', '编辑关于我们', '关于我们', 'BUTTON', 105);

-- 3. 内容小编（EDITOR）默认获得关于我们管理权限；SUPER_ADMIN 会在代码中加载全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('about:view','about:edit');

-- 4. 仅更新未被人工改写的内置角色说明
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们管理'
WHERE code = 'EDITOR'
  AND builtin = 1
  AND description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力管理';
