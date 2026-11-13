-- V20：补齐已有 AI 工具表的数据库所缺的管理权限，可重复执行。
START TRANSACTION;

INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
  ('ai:tool:view', '查看 AI 工具', 'AI 工具', 'MENU',   121),
  ('ai:tool:edit', '编辑 AI 工具', 'AI 工具', 'BUTTON', 122),
  ('ai:tool:use',  '调用 AI 工具', 'AI 工具', 'BUTTON', 123);

-- 超级管理员加载所有已登记权限，内容编辑默认只获得查看权限。
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'EDITOR' AND p.code = 'ai:tool:view';

COMMIT;
