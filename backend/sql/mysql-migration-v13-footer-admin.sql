-- ============================================================
-- V13 迁移：页脚（Footer）接入后台管理
-- 页脚文案 / 快速链接 / 社交图标改由后台「页脚设置」页面（/admin/footer）维护，
-- 存储复用 site_settings 的 footer 组（单例页面配置，不新建表）。
-- 针对已初始化数据库的增量升级；可重复执行（INSERT IGNORE / 带条件 UPDATE）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v13-footer-admin.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. 页脚默认值 = 改造前页脚原始文案/链接。
--    写入后后台表单即为当前线上文案，编辑不会造成内容突变；
--    若不自带 footer 组，后台读取也会回落到同样的默认值（见 FooterService），此处仅是让后台可见可改。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('footer', 'footer.brand_name',     '独角兽工作室',
 '页脚 · 工作室名称', CURRENT_TIMESTAMP),
('footer', 'footer.brand_intro',    '用技术与创意，打造卓越的数字产品。\n我们专注于AI智能开发、Agent开发与大模型学术研究。',
 '页脚 · 工作室简介（\n 换行）', CURRENT_TIMESTAMP),
('footer', 'footer.show_particles', 'false',
 '页脚 · 是否显示左侧装饰粒子动效（true/false）', CURRENT_TIMESTAMP),
('footer', 'footer.links_title',    '快速链接',
 '页脚 · 快速链接栏目标题', CURRENT_TIMESTAMP),
('footer', 'footer.links',          '[{"label":"首页","url":"/","newTab":false},{"label":"作品","url":"/works","newTab":false},{"label":"关于我们","url":"/about","newTab":false},{"label":"学习资料","url":"/learning","newTab":false}]',
 '页脚 · 快速链接（JSON 数组：label/url/newTab）', CURRENT_TIMESTAMP),
('footer', 'footer.social_title',   '关注我们',
 '页脚 · 关注我们栏目标题', CURRENT_TIMESTAMP),
('footer', 'footer.socials',        '[{"icon":"github","iconUrl":"","url":"https://github.com","title":"GitHub"},{"icon":"qq","iconUrl":"","url":"https://qq.com","title":"QQ"}]',
 '页脚 · 社交图标（JSON 数组：icon/iconUrl/url/title）', CURRENT_TIMESTAMP),
('footer', 'footer.email',          '1351094759@qq.com',
 '页脚 · 邮箱地址', CURRENT_TIMESTAMP),
('footer', 'footer.email_label',    '邮箱：',
 '页脚 · 邮箱前缀标签文字', CURRENT_TIMESTAMP),
('footer', 'footer.copyright_left', '© {year} 独角兽工作室 · 用创意点亮未来',
 '页脚 · 版权栏左侧文字（{year} 自动替换为当前年份）', CURRENT_TIMESTAMP),
('footer', 'footer.copyright_right', '每个人都有一颗炽热的 ❤️ and You',
 '页脚 · 版权栏右侧文字', CURRENT_TIMESTAMP);

-- 2. 页脚设置权限码（页面 /admin/footer）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('footer:view', '页脚设置',     '页脚设置', 'MENU',   106),
('footer:edit', '编辑页脚设置', '页脚设置', 'BUTTON', 107);

-- 3. 内容小编（EDITOR）默认获得页脚设置权限；SUPER_ADMIN 会在代码中加载全部权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('footer:view','footer:edit');

-- 4. 仅更新未被人工改写的内置角色说明。
--    历史上 V9/V10/V12 的条件值在部分库中未命中（说明停留在更早的内置值），
--    故这里同时匹配已知的两个内置值，保证说明能收敛到最新；人工改写过的值不会被覆盖。
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们/页脚管理'
WHERE code = 'EDITOR'
  AND builtin = 1
  AND description IN (
    '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们管理',
    '仪表盘 + 作品/新闻/轮播管理'
  );
