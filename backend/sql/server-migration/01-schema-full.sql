-- ==========================================================================
-- Unicorn Studio · 服务器端全量建库脚本（01-schema-full.sql）
-- --------------------------------------------------------------------------
-- 生成方式（历史沿革）：由 backend/sql/tools/gen_merged_schema.py 合并
--   mysql-init.sql（V1-V7 基线）+ mysql-migration-v8..v19 + 权限乱码修复。
--   ⚠️ 该生成脚本从未入库，源迁移文件也已在提交 552fce1 中被删除
--     ⇒ 无法再「改源脚本 → 重新生成」。本文件现在是【唯一的建库来源】，
--       表结构变更请直接改这里（新表按本文件既有范式追加到末尾的 V 段中）。
--
-- 特性：
--   * 幂等：可重复执行。表用 IF NOT EXISTS、种子数据用 INSERT IGNORE、
--           索引走 information_schema 判存后再建。
--   * 非破坏：不含任何 DROP / DELETE / TRUNCATE，不会冲掉服务器上已有数据。
--   * 字符集：utf8mb4 / utf8mb4_0900_ai_ci（与线上库实测一致）。
--
-- 用法（全新服务器）：
--   mysql --default-character-set=utf8mb4 -h<host> -P3306 -u<user> -p < 01-schema-full.sql
--
-- 注意：本脚本只建结构 + 种子数据，不含业务数据。
--       业务数据由 02-data.sql（本地库导出）导入。
-- ==========================================================================
SET NAMES utf8mb4;

-- ==== 基线：V1-V7（原 mysql-init.sql，已并入本文件）====
CREATE DATABASE IF NOT EXISTS unicorn_studio DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE unicorn_studio;
-- ==== V1: 内容基线 ====
-- ============================================================
-- V1 基线：内容表（work/news/carousel/learning_resources/service_item）
-- ============================================================

CREATE TABLE IF NOT EXISTS work (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  slug          VARCHAR(100) NOT NULL UNIQUE,
  title         VARCHAR(200) NOT NULL,
  category      VARCHAR(50),
  cover_url     VARCHAR(255),
  summary       VARCHAR(500),
  content       MEDIUMTEXT COMMENT '富文本，保存前已消毒',
  client_name   VARCHAR(100),
  external_link VARCHAR(255),
  featured      INTEGER NOT NULL DEFAULT 0,
  sort_order    INT NOT NULL DEFAULT 0,
  status        VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/offline',
  view_count    INT NOT NULL DEFAULT 0,
  published_at  DATETIME,
  -- 卡片展示字段（前台占位风格，有 cover_url 后可弱化）
  icon          VARCHAR(50),
  cover_class   VARCHAR(100),
  tag_type      VARCHAR(20) COMMENT 'award/paper',
  authors       VARCHAR(100),
  date_label    VARCHAR(20),
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work' AND INDEX_NAME = 'idx_work_status_sort');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_work_status_sort ON `work` (status, sort_order)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS news (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  slug         VARCHAR(100) NOT NULL UNIQUE,
  title        VARCHAR(200) NOT NULL,
  category     VARCHAR(50),
  cover_url    VARCHAR(255),
  summary      VARCHAR(500),
  content      MEDIUMTEXT,
  author_id    BIGINT COMMENT 'sys_user.id',
  featured     INTEGER NOT NULL DEFAULT 0,
  sort_order   INT NOT NULL DEFAULT 0,
  status       VARCHAR(20) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/offline',
  view_count   INT NOT NULL DEFAULT 0,
  published_at DATETIME,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news' AND INDEX_NAME = 'idx_news_status_sort');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_news_status_sort ON `news` (status, sort_order)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS carousel (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  image_url  VARCHAR(255) NOT NULL,
  title      VARCHAR(100),
  subtitle   VARCHAR(200),
  link_url   VARCHAR(255),
  sort_order INT NOT NULL DEFAULT 0,
  status     INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- V11：页面背景接入轮播图管理。image_url 兼具「静态壁纸」与「视频封面 poster」两种角色
  --       ★ 这三列必须排在 updated_at 之后：V11 是 ALTER TABLE ADD COLUMN，
  --         实录库的 ORDINAL_POSITION 即为「追加在末尾」。若在此处前置，
  --         脚本建出的库与实录库列顺序不一致（列名集合相同、顺序不同）。
  page_key   VARCHAR(32) NOT NULL DEFAULT 'home'  COMMENT '绑定页面：home/about/work-detail',
  media_type VARCHAR(16) NOT NULL DEFAULT 'image' COMMENT 'image=静态壁纸 / video=动态壁纸',
  video_url  VARCHAR(255) NULL                     COMMENT '动态壁纸视频地址，仅 media_type=video 时必填'
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND INDEX_NAME = 'idx_carousel_page_status_sort');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_carousel_page_status_sort ON `carousel` (page_key, status, sort_order)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 学习资源升级（升级方案 3.4：补分类/封面/排序/状态；沿用既有复数表名）
-- 后台管理模块（V8）：补 tags/attachment_url；分类为 learning_category 字典表，资源存分类名
CREATE TABLE IF NOT EXISTS learning_resources (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(200) NOT NULL,
  description VARCHAR(500) NOT NULL,
  content     MEDIUMTEXT COMMENT '富文本，保存前已消毒',
  url         VARCHAR(255),
  category    VARCHAR(50),
  tags        VARCHAR(255) COMMENT '逗号分隔标签',
  cover_url   VARCHAR(255),
  attachment_url VARCHAR(255) COMMENT '附件（上传文件 URL）',
  sort_order  INT NOT NULL DEFAULT 0,
  status      INTEGER NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 学习资料分类字典（V8 后台学习资料管理模块）
CREATE TABLE IF NOT EXISTS learning_category (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(50) NOT NULL UNIQUE,
  description VARCHAR(255),
  sort_order  INT NOT NULL DEFAULT 0,
  status      INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 主要招收方向条目（首页"主要招收方向"）
CREATE TABLE IF NOT EXISTS service_item (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  icon        VARCHAR(50),
  sort_order  INT NOT NULL DEFAULT 0,
  status      VARCHAR(20) NOT NULL DEFAULT 'published' COMMENT 'published/offline',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 我们培养的能力条目（首页"我们培养的能力"）
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

-- ==== V2: 互动表 ====
-- ============================================================
-- V2 互动表：前台用户 / 评论 / 留言
-- 注释：comment.target_type+target_id 为多态关联、无外键约束，
--       删除 work/news 时由服务层同步清理（见实施方案 §3.4）
-- ============================================================

CREATE TABLE IF NOT EXISTS public_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  nickname      VARCHAR(50) NOT NULL,
  email         VARCHAR(100) UNIQUE,
  password      VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  avatar        VARCHAR(255),
  status        INTEGER NOT NULL DEFAULT 1 COMMENT '1正常 0禁言 2注销',
  ban_reason    VARCHAR(255),
  ban_until     DATETIME COMMENT 'NULL=永久禁言',
  register_ip   VARCHAR(45),
  last_login_at DATETIME,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- V19：前台 AI 使用权限。这是「前台 AI 改为登录后可用」这个需求第一次产生的维度，
  --      独立于禁言 status —— 禁言仍由 status=0 表达，两者互不影响（禁言用户照样能用 AI）。
  --      为什么不复用 status：status 已被「登录封禁校验 / 评论拦截 / 前台身份解析」三处依赖，
  --      把 AI 语义塞进去会把那三处的语义一起搅乱，故独立成列。
  --      ★ 本列必须排在 created_at 之后：V19 是 ALTER TABLE ADD COLUMN 且不带 AFTER，
  --        实录库的 ORDINAL_POSITION 即为「追加在末尾」。若在此处前置，
  --        脚本建出的库与实录库列顺序不一致（列名集合相同、顺序不同）。
  ai_enabled    TINYINT NOT NULL DEFAULT 1 COMMENT '1允许使用AI助手 0禁止（独立于禁言 status）'
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME = 'idx_public_user_status');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_public_user_status ON `public_user` (status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `comment` (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_type    VARCHAR(20) NOT NULL COMMENT 'work/news',
  target_id      BIGINT NOT NULL,
  user_id        BIGINT COMMENT 'public_user.id；NULL=游客',
  guest_nickname VARCHAR(50),
  parent_id      BIGINT COMMENT '回复的评论ID（楼中楼）',
  content        VARCHAR(1000) NOT NULL COMMENT '纯文本',
  status         VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  ip_hash        VARCHAR(64),
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_target');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_comment_target ON `comment` (target_type, target_id, status)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_user');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_comment_user ON `comment` (user_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- 评论树子评论查询（CommentMapper.findByParentIdIn）
-- 注：该索引由 v15 迁移引入，init 脚本当时漏了同步，导致全新初始化的库缺它；
--     v18 迁移已补幂等补齐，此处一并补上以保持 init 与迁移结果一致
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_parent');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_comment_parent ON `comment` (parent_id)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- 前台评论列表真分页（CommentService.listApproved，B-08）：
-- parent_id IS NULL 按等值走索引，created_at 在末位使 ORDER BY 不再 filesort
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'idx_comment_root_order');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_comment_root_order ON `comment` (target_type, target_id, status, parent_id, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS message (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(50) NOT NULL,
  contact       VARCHAR(100),
  content       VARCHAR(2000) NOT NULL,
  source        VARCHAR(20) NOT NULL DEFAULT 'contact_form' COMMENT 'contact_form/guestbook',
  status        VARCHAR(20) NOT NULL DEFAULT 'new' COMMENT 'new/replied/closed',
  reply_content TEXT,
  replied_by    BIGINT,
  replied_at    DATETIME,
  ip_hash       VARCHAR(64),
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message' AND INDEX_NAME = 'idx_message_status_time');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_message_status_time ON `message` (status, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ==== V3: RBAC 与系统表 ====
-- ============================================================
-- V3 RBAC 与系统表：管理员 / 角色 / 权限 / 站点设置 / 上传文件 / 审计日志 / 备份
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
  id                        BIGINT PRIMARY KEY AUTO_INCREMENT,
  username                  VARCHAR(50) NOT NULL UNIQUE,
  password                  VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  nickname                  VARCHAR(50),
  avatar                    VARCHAR(255),
  status                    INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0禁用',
  must_change_password      INTEGER NOT NULL DEFAULT 0 COMMENT '首次登录强制改密',
  refresh_token             VARCHAR(500) COMMENT '当前存 UUID；留长以兼容未来 JWT 格式',
  refresh_token_expires_at  DATETIME,
  failed_login_attempts     INT NOT NULL DEFAULT 0 COMMENT '连续失败次数，登录成功/解锁后清零',
  locked_until              DATETIME COMMENT 'NULL=未锁定；账号锁定持久化，重启/多实例不丢',
  last_login_at             DATETIME,
  created_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(50) NOT NULL UNIQUE COMMENT 'SUPER_ADMIN/EDITOR/OPERATOR/AUDITOR',
  name        VARCHAR(50) NOT NULL,
  description VARCHAR(255),
  builtin     INTEGER NOT NULL DEFAULT 0 COMMENT '1=内置不可删改',
  status      INTEGER NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_permission (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  code       VARCHAR(50) NOT NULL UNIQUE,
  name       VARCHAR(50) NOT NULL,
  module     VARCHAR(50),
  type       VARCHAR(20) NOT NULL DEFAULT 'BUTTON' COMMENT 'MENU/BUTTON',
  sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
  role_id       BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE TABLE IF NOT EXISTS site_settings (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name    VARCHAR(50) NOT NULL COMMENT 'basic/contact/social/seo/notify/content',
  setting_key   VARCHAR(100) NOT NULL UNIQUE,
  setting_value TEXT,
  description   VARCHAR(255),
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS upload_file (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name   VARCHAR(255) NOT NULL COMMENT '原始文件名',
  stored_path VARCHAR(255) NOT NULL COMMENT '相对存储路径',
  url         VARCHAR(255) NOT NULL,
  size        BIGINT NOT NULL,
  mime_type   VARCHAR(100),
  uploaded_by BIGINT,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_operation_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id     BIGINT,
  username    VARCHAR(50),
  module      VARCHAR(50),
  action      VARCHAR(50),
  method      VARCHAR(10),
  path        VARCHAR(255),
  params      TEXT COMMENT '已脱敏',
  ip          VARCHAR(45),
  status      VARCHAR(20) NOT NULL COMMENT 'success/failed',
  error_msg   VARCHAR(500),
  duration_ms INT,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME = 'idx_operation_log_time');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_operation_log_time ON `sys_operation_log` (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME = 'idx_operation_log_user');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_operation_log_user ON `sys_operation_log` (username)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS backup_record (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name  VARCHAR(255),
  file_size  BIGINT,
  type       VARCHAR(20) NOT NULL COMMENT 'manual/scheduled',
  status     VARCHAR(20) NOT NULL COMMENT 'running/success/failed',
  error_msg  VARCHAR(500),
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ==== V4: 统计表 ====
-- ============================================================
-- V4 统计表：访问日志 / 每日聚合
-- ============================================================

CREATE TABLE IF NOT EXISTS visit_log (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  page_path  VARCHAR(255) NOT NULL,
  ip_hash    VARCHAR(64) NOT NULL COMMENT 'SHA-256(ip)',
  ua         VARCHAR(255),
  referer    VARCHAR(500),
  visited_at DATETIME NOT NULL
);
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'visit_log' AND INDEX_NAME = 'idx_visit_log_time');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_visit_log_time ON `visit_log` (visited_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'visit_log' AND INDEX_NAME = 'idx_visit_log_ip');
SET @sql := IF(@exists = 0,
  'CREATE INDEX idx_visit_log_ip ON `visit_log` (ip_hash, visited_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS stats_daily (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  stat_date    DATE NOT NULL UNIQUE,
  pv           INT NOT NULL DEFAULT 0,
  uv           INT NOT NULL DEFAULT 0,
  new_users    INT NOT NULL DEFAULT 0,
  new_messages INT NOT NULL DEFAULT 0,
  new_comments INT NOT NULL DEFAULT 0
);

-- ==== V5: 种子 RBAC ====
-- ============================================================
-- V5 种子：权限码（43 个）+ 内置角色 + 角色-权限映射
-- 超管账号不在此创建：由 AdminBootstrap 启动任务按环境变量创建（见实施方案 §5.3）
-- ============================================================

-- 1. 权限码
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
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
('service:list',    '招收方向列表', '主要招收方向', 'MENU', 44),
('service:create',  '新增招收方向', '主要招收方向', 'BUTTON', 45),
('service:edit',    '编辑招收方向', '主要招收方向', 'BUTTON', 46),
('service:delete',  '删除招收方向', '主要招收方向', 'BUTTON', 47),
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
INSERT IGNORE INTO sys_role (code, name, description, builtin) VALUES
('SUPER_ADMIN', '超级管理员', '全部权限（代码级绕过，见实施方案 §5.1）', 1),
('EDITOR',      '内容小编',   '仪表盘 + 作品/新闻/轮播/招收方向/培养能力管理', 1),
('OPERATOR',    '客服运营',   '仪表盘 + 评论/留言/用户管理', 1),
('AUDITOR',     '审计员',     '只读监督', 0);

-- 3. 角色-权限映射（SUPER_ADMIN 不插行，代码级全通过）
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'dashboard:view','work:list','work:create','work:edit','work:delete','work:publish',
  'news:list','news:create','news:edit','news:delete','news:publish',
  'carousel:list','carousel:create','carousel:edit','carousel:delete',
  'service:list','service:create','service:edit','service:delete','file:list','file:upload'
);
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'OPERATOR' AND p.code IN (
  'dashboard:view','comment:list','comment:audit','comment:reply','comment:delete',
  'message:list','message:reply','message:delete',
  'user:list','user:view','user:ban'
);
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'AUDITOR' AND p.code IN (
  'dashboard:view','log:list','work:list','news:list','carousel:list',
  'comment:list','message:list','user:list'
);

-- ==== V6: 种子内容 ====
-- ============================================================
-- V6 种子：站点内容（原 data.sql 数据全量迁移 + 新增示例）
-- data.sql 已停用（升级方案 P1-1），此处保证全量执行后首页内容完整。
-- ============================================================

-- 1. 作品（原 data.sql 4 条原样迁移）
INSERT IGNORE INTO work (slug, title, category, summary, content, external_link, featured, sort_order, status, view_count, icon, cover_class, tag_type, authors, date_label, created_at, updated_at) VALUES
('acm-icpc-gold', 'ACM-ICPC 亚洲区域赛金牌', '竞赛',
'在第 48 届 ACM-ICPC 亚洲区域赛中，团队凭借出色的算法设计与协作能力，从 300+ 支队伍中脱颖而出，斩获金牌。',
'ICPC（国际大学生程序设计竞赛）是全球最具影响力的大学生算法竞赛。本次参赛我们经历了数月的系统训练，重点攻克图论、动态规划与计算几何等核心方向。
赛场上五小时的高强度比拼，考验的不仅是算法功底，更是团队分工与临场决策能力。最终以稳定的发挥拿下金牌，为工作室的算法实力写下有力注脚。
如果你对算法竞赛感兴趣，欢迎通过「加入我们」与我们交流。',
NULL, TRUE, 1, 'published', 0, '🏆', 'bg-gradient-to-br from-amber-100 to-orange-100', 'award', '张三、李四、王五', '2025-12', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('sci-super-resolution', 'SCI 论文：基于深度学习的图像超分辨率重建', '论文',
'提出一种新型注意力机制网络架构，在 Set5、Set14 等基准数据集上 PSNR 指标达到 SOTA，被 IEEE TIP 期刊录用。',
'图像超分辨率重建是计算机视觉的经典难题。本工作提出了一种结合通道注意力与空间注意力的新型网络架构，在保持计算量可控的同时显著提升重建质量。
在 Set5、Set14、BSD100 等多个公开基准上的实验表明，所提方法在 PSNR 与 SSIM 指标上均达到当时最优（SOTA）水平，论文被 IEEE TIP 期刊录用。
本成果是大模型学术研究方向的重要积累，也为后续视觉大模型工作奠定了基础。',
NULL, TRUE, 2, 'published', 0, '📄', 'bg-gradient-to-br from-emerald-100 to-teal-100', 'paper', '赵六、孙七 等', '2026-03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('design-competition-first', '中国大学生计算机设计大赛一等奖', '竞赛',
'作品「智行」—— 基于 Vue 3 与 WebSocket 的实时协作白板应用，荣获交互设计组全国一等奖。',
'「智行」是一款面向远程协作场景的实时白板应用：基于 Vue 3 构建界面，通过 WebSocket 实现多人实时协同编辑，支持画笔、贴纸、文本等丰富元素。
项目从交互设计、技术选型到性能优化均由团队独立完成，经过多轮评审与现场答辩，最终荣获中国大学生计算机设计大赛交互设计组全国一等奖。
该作品完整展示了工作室在前端工程与实时应用领域的能力。',
NULL, TRUE, 3, 'published', 0, '🥇', 'bg-gradient-to-br from-indigo-100 to-violet-100', 'award', '周八、吴九、郑十', '2025-08', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('llm-code-generation', 'SCI 论文：大语言模型在代码生成中的应用研究', '论文',
'探索 GPT 系列模型在自动化代码生成任务中的表现，提出基于检索增强的微调策略，被 ACM TOSEM 期刊收录。',
'代码生成是大语言模型最具落地价值的应用方向之一。本工作系统评估了主流大模型在代码补全、程序合成与缺陷修复任务上的表现差异。
针对大模型在长上下文代码场景中的幻觉问题，我们提出了一种基于检索增强的微调策略，通过引入相似代码片段显著提升生成准确率。
研究成果被 ACM TOSEM 期刊收录，相关方法已应用于工作室的 Agent 开发实践。',
NULL, FALSE, 4, 'published', 0, '', 'bg-gradient-to-br from-rose-100 to-pink-100', 'paper', '陈一、林二 等', '2026-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 2. 主要招收方向条目（原 data.sql 3 条原样迁移）
INSERT IGNORE INTO service_item (title, description, icon, sort_order, status, created_at, updated_at) VALUES
('AI智能应用开发', '使用前沿技术栈，构建高性能、响应式的智能化应用。', '🌐', 1, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('大模型学术论文研究', '聚焦大语言模型前沿方向，开展学术论文撰写与发表。', '🔬', 2, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('Agent开发', '基于大模型开发智能体，支持自主规划、工具调用与任务落地。', '🤖', 3, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 2.1 我们培养的能力条目（首页"我们培养的能力"）
INSERT IGNORE INTO capability_item (title, description, icon, sort_order, status, created_at, updated_at) VALUES
('工程实践能力', '从需求分析到上线部署的完整工程链路训练，形成可复用的开发规范与协作习惯。', '🛠️', 1, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('学术研究能力', '文献调研、实验设计与论文写作的系统训练，具备独立开展前沿课题研究的能力。', '📚', 2, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('团队协作能力', '通过真实项目与竞赛锤炼沟通、分工与协作能力，在团队中高效推进目标落地。', '🤝', 3, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 3. 学习资源（原 data.sql 4 条，按升级方案 3.4 补分类/排序/状态；补正文 content 富文本）
INSERT IGNORE INTO learning_resources (title, description, content, url, category, sort_order, status, created_at, updated_at) VALUES
('后端入门开发手册', '涵盖 java/SpringBoot/SpringCloud 核心基础，适合零基础快速入门',
'<h2>课程大纲</h2><p>本手册覆盖 Java、Spring Boot 与 Spring Cloud 的核心基础，适合零基础同学快速入门。</p><h3>学习路线</h3><ul><li>Java 语法与面向对象</li><li>Spring Boot 自动装配与 Web 开发</li><li>Spring Cloud 微服务与注册中心</li></ul><h3>示例代码</h3><pre><code>@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() {
        return "Hello Unicorn Studio";
    }
}</code></pre><p>学完本手册即可独立完成一个前后端分离项目。</p>',
NULL, '后端', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('Agent 开发实战指南', '从 0 到 1 搭建智能体应用，包含完整案例与可运行代码',
'<h2>从 0 到 1 搭建智能体</h2><p>本指南从大模型 API 调用出发，逐步构建一个可运行的 Agent 应用。</p><h3>核心组件</h3><ul><li>工具调用与函数声明</li><li>记忆与上下文管理</li><li>多轮任务规划</li></ul><h3>最小实现</h3><pre><code>const agent = new Agent({
    model: ''deepseek-chat'',
    tools: [searchTool, codeRunnerTool]
});
await agent.run(''帮我查询今天的天气'');</code></pre><p>完整案例代码见附件，可直接下载运行。</p>',
NULL, 'AI', 2, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('大模型应用开发教程', '基于大模型的应用开发指南，涵盖 API 调用、数据处理与优化策略',
'<h2>教程简介</h2><p>本教程讲解大模型应用开发全流程：API 调用、提示词工程、数据处理与性能优化。</p><h3>章节安排</h3><ul><li>第一章：模型 API 与流式输出</li><li>第二章：提示词工程最佳实践</li><li>第三章：检索增强与数据清洗</li><li>第四章：部署与成本优化</li></ul><p>学完后你将能够独立完成大模型应用的选型与落地。</p>',
NULL, 'AI', 3, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('前端开发进阶手册', '深入理解 Vue3、React、TypeScript 等前端技术栈，提升开发效率',
'<h2>手册内容</h2><p>深入理解 Vue3、React、TypeScript 等主流前端技术栈，提升工程化开发效率。</p><h3>推荐学习顺序</h3><ul><li>TypeScript 类型系统</li><li>Vue3 组合式 API 与响应式原理</li><li>React Hooks 与状态管理</li><li>Vite 构建与工程化实践</li></ul><h3>速查片段</h3><pre><code>const [count, setCount] = useState(0);
useEffect(() => {
    document.title = `Count: ${count}`;
}, [count]);</code></pre><p>结合本项目后台源码（Vue3 + Element Plus）实战练习效果更佳。</p>',
NULL, '前端', 4, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 4. 新闻示例（2 条）
INSERT IGNORE INTO news (slug, title, category, summary, content, status, sort_order, published_at, created_at, updated_at) VALUES
('studio-welcome-2026', '独角兽工作室 2026 招新开启', '工作室动态',
'面向全校开放算法竞赛、大模型应用、前端工程三个方向的新人招募，报名截止 9 月 30 日。',
'独角兽工作室 2026 年度招新正式启动！本次招新面向全校学生，开放算法竞赛、大模型应用开发、前端工程三个方向。
招新流程：线上报名 → 笔试 → 面试 → 暑期训练营。零基础同学也可报名，工作室提供系统化培养体系。
报名请通过「加入我们」页面提交申请。',
'published', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('llm-workshop-2026', '大模型应用开发工作坊圆满举办', '学术活动',
'为期两周的工作坊覆盖 Prompt 工程、Agent 框架与部署实践，参与同学超过 120 人。',
'工作室举办的大模型应用开发工作坊顺利收官。本次工作坊共 6 期课程，涵盖 Prompt 工程、检索增强、Agent 框架搭建与生产部署实践。
工作坊采用"理论 + 动手"模式，每期均配有可运行的实验项目，参与同学超过 120 人。
后续工作坊安排请关注工作室动态。',
'published', 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 5. 轮播图（停用占位，待上传真实图片后在后台启用）
INSERT IGNORE INTO carousel (image_url, title, subtitle, sort_order, status, created_at, updated_at) VALUES
('/uploads/placeholder-1.jpg', '独角兽工作室', '培养面向未来的工程人才', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('/uploads/placeholder-2.jpg', '2026 招新进行中', '加入我们，一起创造', 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ==== V7: SQL 监控 ====
-- SQL 监控权限码（Druid 连接池统计，页面 /admin/sql-monitor）
-- 不插角色映射行：SUPER_ADMIN 由 AdminPermissionService 代码级全量加载，新增码自动生效
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('system:sql', 'SQL 监控', '系统', 'MENU', 93);

-- ==== V8: 学习资料管理 ====
-- 学习资料管理权限码（页面 /admin/learning 与 /admin/learning/categories）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('learning:list',     '学习资料列表', '学习资料', 'MENU', 94),
('learning:create',   '新增学习资料', '学习资料', 'BUTTON', 95),
('learning:edit',     '编辑学习资料', '学习资料', 'BUTTON', 96),
('learning:delete',   '删除学习资料', '学习资料', 'BUTTON', 97),
('learning:publish',  '上下架资料',   '学习资料', 'BUTTON', 98),
('learning:category', '资料分类管理', '学习资料', 'MENU', 99);

-- 内容小编（EDITOR）同步获得学习资料管理权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'learning:list','learning:create','learning:edit','learning:delete','learning:publish','learning:category'
);

-- ==== V10: 我们培养的能力管理 ====
-- 培养能力管理权限码（页面 /admin/capabilities）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('capability:list',   '培养能力列表', '我们培养的能力', 'MENU', 100),
('capability:create', '新增培养能力', '我们培养的能力', 'BUTTON', 101),
('capability:edit',   '编辑培养能力', '我们培养的能力', 'BUTTON', 102),
('capability:delete', '删除培养能力', '我们培养的能力', 'BUTTON', 103);

-- 内容小编（EDITOR）同步获得培养能力管理权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'capability:list','capability:create','capability:edit','capability:delete'
);

-- ==== V12: 关于我们页面内容管理 ====
-- 页面内容存 site_settings 的 about 组（单例页面配置，不新建表）；
-- 默认值 = 改造前页面原始文案，保证「表为空」时前台表现与改造前一致。
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

-- 关于我们管理权限码（页面 /admin/about）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('about:view', '关于我们',     '关于我们', 'MENU',   104),
('about:edit', '编辑关于我们', '关于我们', 'BUTTON', 105);

-- 内容小编（EDITOR）同步获得关于我们管理权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('about:view','about:edit');

-- 内置角色说明同步
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们管理'
WHERE code = 'EDITOR' AND builtin = 1;

-- ==== V13: 页脚设置管理 ====
-- 页脚配置存 site_settings 的 footer 组（单例页面配置，不新建表）；
-- 默认值 = 改造前页脚的原始文案/链接，保证「表为空」时前台表现与改造前一致。
-- copyright_left 中的 {year} 由前台替换为当前年份（避免版权年份被冻结）。
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

-- 页脚设置权限码（页面 /admin/footer）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('footer:view', '页脚设置',     '页脚设置', 'MENU',   106),
('footer:edit', '编辑页脚设置', '页脚设置', 'BUTTON', 107);

-- 内容小编（EDITOR）同步获得页脚设置权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('footer:view','footer:edit');

-- 内置角色说明同步
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们/页脚管理'
WHERE code = 'EDITOR' AND builtin = 1;

-- ==== V14: 数据保留策略（审计日志 / SQL 监控） ====
-- 审计日志（sys_operation_log）与 SQL 监控（Druid 内存统计）仅保留最近 N 天；
-- N 存 site_settings 的 retention 组（默认 7，可选 7/15/30/90），后台「数据保留」页（/admin/retention）可改，
-- 每次清理都实时读取，改后即时生效（无需重启）。执行周期由 app.retention.cron 决定（默认每日 03:30）。
-- sql_monitor_last_reset / last_run_* 为系统运行时写入的观测键，初值留空，请勿手工修改。
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

-- 数据保留权限码（页面 /admin/retention）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('retention:view', '数据保留',     '数据保留', 'MENU',   108),
('retention:edit', '编辑保留策略', '数据保留', 'BUTTON', 109),
('retention:run',  '立即执行清理', '数据保留', 'BUTTON', 110);

-- 数据保留属系统级破坏性操作（物理删除），仅 SUPER_ADMIN（代码内加载全部权限）可用，不下发给 EDITOR。

-- ==== V16: AI 助手模块（Spring AI） ====
-- AI 配置统一存 ai 组，后台「AI 配置」页（/admin/ai-config）维护，改后即时生效（无需重启）。
-- 代码位于 com.unicorn.studio.AIWorker（Controller/Service/Mapper/VO/DTO/tool 分层）。
-- API Key 以 AES-256-GCM 加密后落库（密钥取环境变量 APP_CRYPTO_SECRET），禁止明文保存；
-- 因此 ai.api_key 初值留空 —— 切勿把真实密钥写进 SQL 或提交到版本库。
INSERT IGNORE INTO site_settings (group_name, setting_key, setting_value, description, updated_at) VALUES
('ai', 'ai.api_key', '',
 'AI 助手 · API Key（AES-256-GCM 加密存储，后台填写，勿手工明文写入）', CURRENT_TIMESTAMP),
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

-- AI 配置权限码（页面 /admin/ai-config）；ai:test 独立成码（消耗真实 API 额度，需与编辑分权）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('ai:view', 'AI 配置',       'AI 配置', 'MENU',   111),
('ai:edit', '编辑 AI 配置',   'AI 配置', 'BUTTON', 112),
('ai:test', '测试 AI 连通性', 'AI 配置', 'BUTTON', 113);

-- 内容小编（EDITOR）获得 AI 配置查看与编辑权限；不下发 ai:test（仅 SUPER_ADMIN 可用）
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('ai:view','ai:edit');

-- ==== V17: 加入我们页面管理 ====
-- 「加入我们」页面文案与社群二维码改由后台「加入我们」页（/admin/join）维护，
-- 存储复用 site_settings 的 join 组（单例页面配置，不新建表）；前台读取 GET /api/join。
-- join.paragraphs 为纯文本，段落间用空行分隔（\n\n 为 MySQL 字符串转义，落库为真实换行 0x0A），
-- 读取时切分为段落数组；切勿写成 \\n\\n（会变成字面量反斜杠导致前台只渲染一段）。
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

-- 加入我们管理权限码（页面 /admin/join）
INSERT IGNORE INTO sys_permission (code, name, module, type, sort_order) VALUES
('join:view', '加入我们',     '加入我们', 'MENU',   114),
('join:edit', '编辑加入我们', '加入我们', 'BUTTON', 115);

-- 内容小编（EDITOR）同步获得加入我们管理权限
INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN ('join:view','join:edit');

-- 内置角色说明同步
UPDATE sys_role
SET description = '仪表盘 + 作品/新闻/轮播/招收方向/培养能力/关于我们/页脚/加入我们管理'
WHERE code = 'EDITOR' AND builtin = 1;



-- ##########################################################################
-- 以下来自 mysql-migration-v8-learning-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v9-service-item-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v10-capability-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v11-page-background.sql
-- ##########################################################################

-- ============================================================
-- V11 迁移：页面背景接入轮播图管理
-- 为 carousel 表补充「绑定页面 / 资源类型 / 动态壁纸视频」三个维度，
-- 使「关于我们」与「作品详情」页背景可由后台轮播图管理统一配置。
-- 针对已初始化数据库的增量升级；可重复执行（列/索引已存在则跳过）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v11-page-background.sql
-- ============================================================

SET NAMES utf8mb4;

-- 1. page_key：绑定页面（home=首页 Hero 轮播 / about=关于我们 / work-detail=作品详情）
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND COLUMN_NAME = 'page_key'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE carousel ADD COLUMN page_key VARCHAR(32) NOT NULL DEFAULT ''home'' COMMENT ''绑定页面：home/about/work-detail''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. media_type：资源类型（image=静态壁纸 / video=动态壁纸）
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND COLUMN_NAME = 'media_type'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE carousel ADD COLUMN media_type VARCHAR(16) NOT NULL DEFAULT ''image'' COMMENT ''image=静态壁纸 / video=动态壁纸''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. video_url：动态壁纸视频地址（image_url 继续承担静态图 / 视频封面 poster 的角色）
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND COLUMN_NAME = 'video_url'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE carousel ADD COLUMN video_url VARCHAR(255) NULL COMMENT ''动态壁纸视频地址，仅 media_type=video 时必填''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. 复合索引：前台按 page_key + status 取单条生效背景
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel'
    AND INDEX_NAME = 'idx_carousel_page_status_sort'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE carousel ADD INDEX idx_carousel_page_status_sort (page_key, status, sort_order)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. 既有数据回填（DEFAULT 已兜底，此处仅保证语义正确、可安全重复执行）
UPDATE carousel SET page_key   = 'home'  WHERE page_key   IS NULL OR page_key   = '';
UPDATE carousel SET media_type = 'image' WHERE media_type IS NULL OR media_type = '';

-- ##########################################################################
-- 以下来自 mysql-migration-v12-about-page-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v13-footer-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v14-data-retention.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v15-index-tuning.sql
-- ##########################################################################

-- ============================================================
-- V15 迁移：补齐高频读路径缺失的索引
-- 背景：V15 Redis 缓存方案（docs/Redis缓存设计设计方案.md 附录 B）实施前的配套变更。
--       缓存不能替代索引 —— 冷启动、缓存未命中、缓存被清空时，下列查询仍是全表扫描：
--         · learning_resources  无任何二级索引 → LearningService.findAll() 全表扫描 + filesort
--         · capability_item     无任何二级索引 → CapabilityService.findPublished() 全表扫描
--         · service_item        status 无索引    → ServiceItemService.findPublished() 全表扫描
--         · site_settings       group_name 无索引 → FooterService/AboutPageService/RetentionService 每次全表扫描
--         · sys_user            refresh_token 无索引 → AuthService.refresh() 全表扫描
--         · backup_record       created_at 无索引 → BackupService.cleanupOldBackups() 全表扫描 + filesort
--         · comment             parent_id 无索引 → CommentService.listApproved() 子评论查询全表扫描
--         · sys_operation_log   module/status 无索引 → 后台日志筛选扫描
-- 针对已初始化数据库的增量升级；可重复执行（索引已存在则跳过）。
-- 执行方式：mysql --default-character-set=utf8mb4 -u root -p unicorn_studio < mysql-migration-v15-index-tuning.sql
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. learning_resources：status + sort_order + id
--    服务 LearningService.findAll() 的 WHERE status=1 ORDER BY sort_order, id
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_resources'
    AND INDEX_NAME = 'idx_learning_status_sort'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE learning_resources ADD INDEX idx_learning_status_sort (status, sort_order, id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. capability_item：status + sort_order + id
--    服务 CapabilityService.findPublished() / CapabilityAdminService 列表
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_item'
    AND INDEX_NAME = 'idx_capability_status_sort'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE capability_item ADD INDEX idx_capability_status_sort (status, sort_order, id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3. service_item：status + sort_order + id
--    服务 ServiceItemService.findPublished() / ServiceItemAdminService 列表
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'service_item'
    AND INDEX_NAME = 'idx_service_item_status_sort'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE service_item ADD INDEX idx_service_item_status_sort (status, sort_order, id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4. site_settings：group_name + setting_key
--    服务 SiteSettingsMapper.findByGroupName()（footer/about/retention 三处高频全表扫描）
--    注：setting_key 已是 UNIQUE，此处把 group_name 放前导列以消除分组扫描
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_settings'
    AND INDEX_NAME = 'idx_site_settings_group'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE site_settings ADD INDEX idx_site_settings_group (group_name, setting_key)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 5. sys_user：refresh_token 前缀索引
--    服务 SysUserMapper.findByRefreshToken()（每次后台 token 刷新一次全表扫描）
--    说明：refresh_token 为 VARCHAR(500)，UTF8MB4 下整列索引会超键长上限，
--          故取前 64 字符（UUID 为 36 字符，实际唯一性充分）。
--          若已按 Redis 方案把刷新令牌迁出 MySQL，本索引可跳过。
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user'
    AND INDEX_NAME = 'idx_sys_user_refresh_token'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE sys_user ADD INDEX idx_sys_user_refresh_token (refresh_token(64))',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 6. backup_record：created_at
--    服务 BackupService.cleanupOldBackups() 的 ORDER BY created_at DESC
--    （每天 02:00 定时任务 + 每次手动备份后各执行一次全表扫描 + filesort）
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_record'
    AND INDEX_NAME = 'idx_backup_record_created'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE backup_record ADD INDEX idx_backup_record_created (created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 7. comment：parent_id
--    服务 CommentMapper.findByParentIdIn()（评论树的子评论查询）
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment'
    AND INDEX_NAME = 'idx_comment_parent'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `comment` ADD INDEX idx_comment_parent (parent_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 8. sys_operation_log：module + status
--    服务后台审计日志筛选（OperationLogController 的 module / status 过滤）
--    注：created_at 与 username 已在 mysql-init.sql 建索引
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log'
    AND INDEX_NAME = 'idx_operation_log_module_status'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE sys_operation_log ADD INDEX idx_operation_log_module_status (module, status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 说明：以下「未纳入本批」的索引及原因（避免后人重复评估）
--
-- · work.featured / work.category、news.category
--     现有 idx_work_status_sort(status, sort_order) / idx_news_status_sort(status, sort_order)
--     可服务 status 前缀，featured/category 为后置过滤。这两类查询结果集小且将被
--     Redis 缓存覆盖，首期不加索引；待内容量增长后按慢查询日志（Druid slow-sql-millis=2000）评估。
-- · public_user.created_at、public_user.ban_until
--     仅后台列表筛选与每日 03:00 自动解封使用，频率低，暂不加。
-- · message.contact / message.name / message.content
--     均为 LIKE 模糊匹配，B-Tree 索引无效，需要全文索引或外部检索，不在本批范围。
-- · visit_log.referer
--     基数高且仅用于仪表盘 GROUP BY 聚合；该聚合将被 Redis 短 TTL 缓存覆盖（方案 §3.3 B11），
--     且覆盖索引代价大，暂不加。
-- ============================================================

-- ##########################################################################
-- 以下来自 mysql-migration-v16-ai-assistant.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v17-join-page-admin.sql
-- ##########################################################################

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

-- ##########################################################################
-- 以下来自 mysql-migration-v18-comment-pagination-index.sql
-- ##########################################################################

-- ============================================================
-- V18 迁移：评论列表真分页所需的复合索引（B-08 前置）
--
-- 背景：CommentService.listApproved() 是「假分页」—— 先把某作品/新闻下
--       全部 approved 评论 selectList 拉进 JVM，再 filter 出根评论、再 subList 切页。
--       页大小完全不影响 DB 侧开销：5000 条评论的热门作品，翻任何一页都要把
--       5000 行（每行含最多 1000 字 content）全量拉回。
--
-- 改成真分页（LIMIT + COUNT）之前【必须先补索引】，否则：
--   · COUNT 仍要扫全表 → 可能比现在的假分页更慢
--   · ORDER BY created_at 仍走 filesort
-- 这是文档明确标注的前置条件（《后端优化方案.md》B-08 的注意事项）。
--
-- 索引设计：(target_type, target_id, status, parent_id, created_at)
--   · 前三列等值匹配，与既有 idx_comment_target 的前缀一致；
--   · parent_id 的 IS NULL 在 MySQL 中按等值处理，可继续走索引；
--   · created_at 是 ORDER BY 列，放在最后 ⇒ 索引本身有序，消除 filesort。
--   实测 EXPLAIN 对比见文件末尾。
--
-- 说明：本索引以 idx_comment_target (target_type, target_id, status) 为严格前缀，
--       因此后者在功能上已冗余（任何能用它的查询都能用新索引）。
--       本脚本【不删除】它 —— 删索引不属于本次范围，且留着只是少量写放大。
--       若要清理，执行：ALTER TABLE `comment` DROP INDEX idx_comment_target;
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1. comment：根评论分页查询的复合索引
--    服务 CommentService.listApproved()：
--      WHERE target_type=? AND target_id=? AND status='approved'
--        AND parent_id IS NULL
--      ORDER BY created_at ASC
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment'
    AND INDEX_NAME = 'idx_comment_root_order'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `comment` ADD INDEX idx_comment_root_order (target_type, target_id, status, parent_id, created_at)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2. comment：补 idx_comment_parent（仅影响「按 mysql-init.sql 全新初始化」的库）
--    该索引由 v15 迁移引入，但 mysql-init.sql 当时漏了同步 ——
--    全新初始化的环境因此缺少这个索引，直到 v15 迁移被再次执行才会补上。
--    这里做成幂等，已在 v15 加过的库不受影响。
-- ------------------------------------------------------------
SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment'
    AND INDEX_NAME = 'idx_comment_parent'
);
SET @sql := IF(@exists = 0,
  'ALTER TABLE `comment` ADD INDEX idx_comment_parent (parent_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 实测记录（2026-09-13，真实库）
--
-- 补索引前：
--   EXPLAIN SELECT * FROM comment WHERE target_type='work' AND target_id=1
--     AND status='approved' AND parent_id IS NULL ORDER BY created_at ASC;
--   → key=idx_comment_target, Extra=Using where; Using filesort   ← 排序无法走索引
--
-- 补索引后：
--   → key=idx_comment_root_order, Extra=Using where               ← filesort 消失
--
-- 注：验证时该表为 0 行，故「行数」指标不具参考性；filesort 的有无由
--     查询与索引结构决定，与数据量无关，因此该结论成立。
-- ============================================================

-- ##########################################################################
-- 以下来自 mysql-migration-v19-ai-memory.sql（本次仅并入 public_user.ai_enabled 一列）
-- ##########################################################################

-- ============================================================
-- V19 迁移：前台 AI 使用权限列 public_user.ai_enabled
-- 背景：前台 AI 由「匿名可用」改为「登录后可用」，需要一个独立于禁言的 AI 使用开关
--       （设计方案 §3.2 登录门禁）。1 允许使用 / 0 禁止。
--       为什么不复用 status：status 已被「登录封禁校验 / 评论拦截 / 前台身份解析」三处依赖，
--       把 AI 语义塞进去会把那三处的语义一起搅乱，故独立成列。禁言用户照样能用 AI。
-- 向后兼容：仅新增列，不改动、不删除任何现有表结构与数据。
-- 幂等：列走 information_schema 判存块（MySQL 8 的 ADD COLUMN 没有 IF NOT EXISTS）。
-- 非破坏：无 DROP / DELETE / TRUNCATE。
-- 执行方式：随本文件一起执行；亦可用于已初始化库的增量重跑。
-- ============================================================
SET NAMES utf8mb4;

-- public_user 补列 ai_enabled（存在则跳过，写法同 V8 的 learning_resources 补列）
SET @add_ai_enabled := (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND COLUMN_NAME = 'ai_enabled'),
    'SELECT 1',
    'ALTER TABLE public_user ADD COLUMN ai_enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''1允许使用AI助手 0禁止（独立于禁言 status）'''
  )
);
PREPARE stmt FROM @add_ai_enabled; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ##########################################################################
-- 以下来自 mysql-fix-permission-mojibake.sql（历史乱码修复，幂等）
-- ##########################################################################

-- =============================================================
-- 修复 sys_permission 表 id 41-50（learning / service 权限码）中文乱码
-- -------------------------------------------------------------
-- 现象：name / module 字段出现「瀛︿範璧勬枡鍒楄〃」类 mojibake
-- 根因：早期（V9 service、learning 模块）执行迁移时 mysql 客户端未加
--       --default-character-set=utf8mb4，UTF-8 字节被按 GBK 解码后写入。
--       V10 之后的迁移（capability/about/footer/retention）均带 utf8mb4，故未受影响。
-- 修复：以 code（UNIQUE）为准，重写为 mysql-init.sql 中的正确中文。
-- 幂等：重复执行仅再次写入相同正确值，无副作用。
-- =============================================================
SET NAMES utf8mb4;

-- learning 权限码（id 41-46）
UPDATE sys_permission SET name = '学习资料列表', module = '学习资料' WHERE code = 'learning:list';
UPDATE sys_permission SET name = '新增学习资料', module = '学习资料' WHERE code = 'learning:create';
UPDATE sys_permission SET name = '编辑学习资料', module = '学习资料' WHERE code = 'learning:edit';
UPDATE sys_permission SET name = '删除学习资料', module = '学习资料' WHERE code = 'learning:delete';
UPDATE sys_permission SET name = '上下架资料',   module = '学习资料' WHERE code = 'learning:publish';
UPDATE sys_permission SET name = '资料分类管理', module = '学习资料' WHERE code = 'learning:category';

-- service 权限码（id 47-50）
UPDATE sys_permission SET name = '招收方向列表', module = '主要招收方向' WHERE code = 'service:list';
UPDATE sys_permission SET name = '新增招收方向', module = '主要招收方向' WHERE code = 'service:create';
UPDATE sys_permission SET name = '编辑招收方向', module = '主要招收方向' WHERE code = 'service:edit';
UPDATE sys_permission SET name = '删除招收方向', module = '主要招收方向' WHERE code = 'service:delete';

-- ==========================================================================
-- 完成。执行 03-verify.sql 校验结构，再导入 02-data.sql。
-- ==========================================================================
