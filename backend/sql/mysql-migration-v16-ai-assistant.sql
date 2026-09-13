-- ============================================================
-- V16 迁移：AI 助手模块（基于 Spring AI）
-- - AI 配置统一存 site_settings 的 ai 组（后台「AI 配置」页维护，改后即时生效，无需重启）；
-- - API Key 以 AES-256-GCM 加密后落库（禁用明文），加密密钥来自环境变量 APP_CRYPTO_SECRET；
-- - 权限码 ai:view / ai:edit / ai:test。
-- 针对已初始化数据库的增量升级；可重复执行（INSERT IGNORE）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v16-ai-assistant.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. AI 配置默认值。
--    注意：ai.api_key 故意留空 —— 禁止把真实密钥写进 SQL/版本库；
--    管理员登录后台「AI 配置」页填写后由服务端加密写入。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('ai', 'ai.api_key', '',       'AI 助手 · API Key（AES-256-GCM 加密存储，后台填写，勿手工明文写入）', CURRENT_TIMESTAMP),
('ai', 'ai.api_url', 'https://open.bigmodel.cn/api/paas/v4',
                               'AI 助手 · API 地址（兼容 OpenAI 协议的 base URL，如 https://api.openai.com/v1）', CURRENT_TIMESTAMP),
('ai', 'ai.model', 'GLM-4.5-Air',
                               'AI 助手 · 模型名称（需与所填 API 地址的供应商一致）', CURRENT_TIMESTAMP),
('ai', 'ai.system_prompt',
 '你是独角兽工作室的AI助手，名叫小独角兽。你友好、专业，擅长回答关于工作室、技术学习和编程的问题。请用中文回答。',
                               'AI 助手 · 系统提示词（人设与回答风格）', CURRENT_TIMESTAMP),
('ai', 'ai.max_tokens', '1024', 'AI 助手 · 单次回复最大 token 数', CURRENT_TIMESTAMP),
('ai', 'ai.temperature', '0.7', 'AI 助手 · 采样温度（0~2，越大越随机）', CURRENT_TIMESTAMP),
('ai', 'ai.enabled', 'true',    'AI 助手 · 功能总开关（false 则前台对话不可用）', CURRENT_TIMESTAMP);

-- 2. AI 配置管理权限码（页面 /admin/ai-config）。
--    ai:test 独立成码：连通性测试会消耗真实 API 额度，需与「编辑」分权。
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('ai:view', 'AI 配置',         'AI 配置', 'MENU',   111),
('ai:edit', '编辑 AI 配置',     'AI 配置', 'BUTTON', 112),
('ai:test', '测试 AI 连通性',   'AI 配置', 'BUTTON', 113);

-- 3. 内容小编（EDITOR）同步获得 AI 配置查看与编辑权限。
--    不含 ai:test：连通性测试直接消耗外部 API 费用，仅 SUPER_ADMIN 可用（代码级全量加载）。
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('ai:view','ai:edit');
