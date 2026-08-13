-- ============================================================
-- V5 种子：权限码（39 个）+ 内置角色 + 角色-权限映射
-- 超管账号不在此创建：由 AdminBootstrap 启动任务按环境变量创建（见实施方案 §5.3）
-- ============================================================

-- 1. 权限码
INSERT INTO sys_permission (code, name, module, type, sort_order) VALUES
('dashboard:view',  '查看仪表盘', '仪表盘', 'MENU', 10),
('work:list',       '作品列表',   '作品',   'MENU', 20),
('work:create',     '新增作品',   '作品',   'BUTTON', 21),
('work:edit',       '编辑作品',   '作品',   'BUTTON', 22),
('work:delete',     '删除作品',   '作品',   'BUTTON', 23),
('work:publish',    '上下架作品', '作品',   'BUTTON', 24),
('news:list',       '新闻列表',   '新闻',   'MENU', 30),
('news:create',     '新增新闻',   '新闻',   'BUTTON', 31),
('news:edit',       '编辑新闻',   '新闻',   'BUTTON', 32),
('news:delete',     '删除新闻',   '新闻',   'BUTTON', 33),
('news:publish',    '发布新闻',   '新闻',   'BUTTON', 34),
('carousel:list',   '轮播图列表', '轮播图', 'MENU', 40),
('carousel:create', '新增轮播图', '轮播图', 'BUTTON', 41),
('carousel:edit',   '编辑轮播图', '轮播图', 'BUTTON', 42),
('carousel:delete', '删除轮播图', '轮播图', 'BUTTON', 43),
('comment:list',    '评论列表',   '评论',   'MENU', 50),
('comment:audit',   '审核评论',   '评论',   'BUTTON', 51),
('comment:reply',   '回复评论',   '评论',   'BUTTON', 52),
('comment:delete',  '删除评论',   '评论',   'BUTTON', 53),
('message:list',    '留言列表',   '留言',   'MENU', 60),
('message:reply',   '回复留言',   '留言',   'BUTTON', 61),
('message:delete',  '删除留言',   '留言',   'BUTTON', 62),
('user:list',       '用户列表',   '用户',   'MENU', 70),
('user:view',       '用户详情',   '用户',   'BUTTON', 71),
('user:ban',        '禁言/解禁',  '用户',   'BUTTON', 72),
('user:delete',     '注销用户',   '用户',   'BUTTON', 73),
('admin:list',      '管理员列表', '系统',   'MENU', 80),
('admin:create',    '新增管理员', '系统',   'BUTTON', 81),
('admin:edit',      '编辑管理员', '系统',   'BUTTON', 82),
('admin:delete',    '删除管理员', '系统',   'BUTTON', 83),
('role:list',       '角色列表',   '系统',   'MENU', 84),
('role:create',     '新增角色',   '系统',   'BUTTON', 85),
('role:edit',       '编辑角色',   '系统',   'BUTTON', 86),
('role:delete',     '删除角色',   '系统',   'BUTTON', 87),
('system:settings', '系统设置',   '系统',   'MENU', 88),
('system:backup',   '数据备份',   '系统',   'MENU', 89),
('log:list',        '审计日志',   '系统',   'MENU', 90),
('file:list',       '文件列表',   '系统',   'MENU', 91),
('file:upload',     '文件上传',   '系统',   'BUTTON', 92);

-- 2. 内置角色（builtin=1 不可删改）
INSERT INTO sys_role (code, name, description, builtin) VALUES
('SUPER_ADMIN', '超级管理员', '全部权限（代码级绕过，见实施方案 §5.1）', 1),
('EDITOR',      '内容小编',   '仪表盘 + 作品/新闻/轮播管理', 1),
('OPERATOR',    '客服运营',   '仪表盘 + 评论/留言/用户管理', 1),
('AUDITOR',     '审计员',     '只读监督', 0);

-- 3. 角色-权限映射（SUPER_ADMIN 不插行，代码级全通过）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'dashboard:view','work:list','work:create','work:edit','work:delete','work:publish',
  'news:list','news:create','news:edit','news:delete','news:publish',
  'carousel:list','carousel:create','carousel:edit','carousel:delete','file:list','file:upload'
);
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'OPERATOR' AND p.code IN (
  'dashboard:view','comment:list','comment:audit','comment:reply','comment:delete',
  'message:list','message:reply','message:delete',
  'user:list','user:view','user:ban'
);
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'AUDITOR' AND p.code IN (
  'dashboard:view','log:list','work:list','news:list','carousel:list',
  'comment:list','message:list','user:list'
);
