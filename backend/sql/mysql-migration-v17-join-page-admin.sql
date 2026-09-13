-- ============================================================
-- V17 迁移：「加入我们」页面接入后台管理
-- 「加入我们」文案 + 社群二维码改由后台「加入我们」页面（/admin/join）维护，
-- 存储复用 site_settings 的 join 组（单例页面配置，不新建表）；前台读取 GET /api/join。
-- 针对已初始化数据库的增量升级；可重复执行（INSERT IGNORE / 带条件 UPDATE）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v17-join-page-admin.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 加入我们默认值 = 改造前 JoinPage.vue 的原始文案。
--    写入后后台表单即为当前线上文案，编辑不会造成内容突变；
--    若不自带 join 组，后台读取也会回落到同样的默认值（见 JoinPageService），此处仅是让后台可见可改。
--    注意：join.paragraphs 是「纯文本 + 空行分段」，不是 JSON；此处的 \n\n 是 MySQL 字符串
--    转义（落库为真实换行符 0x0A），切勿写成 \\n\\n —— 那会变成字面量反斜杠导致前台只渲染一段。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('join', 'join.eyebrow',    '',
 '加入我们 · 顶部小标题（留空则不显示）', CURRENT_TIMESTAMP),
('join', 'join.title',      '加入我们',
 '加入我们 · 主标题', CURRENT_TIMESTAMP),
('join', 'join.subtitle',   '欢迎加入独角兽工作室交流社群',
 '加入我们 · 副标题', CURRENT_TIMESTAMP),
('join', 'join.paragraphs', '在这里我们分享前沿技术、实战项目与设计创意，共同成长、一起探索数字世界的更多可能。\n\n扫描下方二维码，即可加入社群与我们直接交流。',
 '加入我们 · 正文段落，段落间用空行分隔', CURRENT_TIMESTAMP),
('join', 'join.qr_image',   '',
 '加入我们 · 社群二维码图片（留空则使用站点默认二维码）', CURRENT_TIMESTAMP),
('join', 'join.qr_caption', 'QQ扫码加入社群',
 '加入我们 · 二维码下方说明文字', CURRENT_TIMESTAMP);

-- 2. 加入我们管理权限码（页面 /admin/join）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('join:view', '加入我们',     '加入我们', 'MENU',   114),
('join:edit', '编辑加入我们', '加入我们', 'BUTTON', 115);

-- 3. 内容小编（EDITOR）默认获得加入我们管理权限；SUPER_ADMIN 会在代码中加载全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('join:view','join:edit');

-- 4. 仅更新未被人工改写的内置角色说明。
--    历史上 V9/V10/V12/V13 的条件值在部分库中未命中（说明停留在更早的内置值），
--    故这里同时匹配已知的多个内置值，保证说明能收敛到最新；人工改写过的值不会被覆盖。
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们/页脚/加入我们管理'
WHERE code = 'EDITOR'
  AND builtin = 1
  AND description IN (
    '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们/页脚管理',
    '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们管理',
    '仪表盘 + 作品/新闻/轮播管理'
  );
