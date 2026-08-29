-- ============================================================
-- Unicorn Studio MySQL 8 初始化脚本
-- 由 Flyway V1-V7 迁移脚本合并生成（2026-08）
-- 用法: mysql -u root -p < backend/sql/mysql-init.sql
-- ============================================================
SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS unicorn_studio DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE unicorn_studio;
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS work, news, carousel, learning_resources, service_item, public_user, `comment`,
  message, visit_log, stats_daily, sys_user, sys_role, sys_permission, sys_user_role,
  sys_role_permission, site_settings, upload_file, sys_operation_log, backup_record;
SET FOREIGN_KEY_CHECKS = 1;

-- ==== V1: 内容基线 ====
-- ============================================================
-- V1 基线：内容表（work/news/carousel/learning_resources/service_item）
-- ============================================================

CREATE TABLE work (
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
CREATE INDEX idx_work_status_sort ON work (status, sort_order);

CREATE TABLE news (
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
CREATE INDEX idx_news_status_sort ON news (status, sort_order);

CREATE TABLE carousel (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  image_url  VARCHAR(255) NOT NULL,
  title      VARCHAR(100),
  subtitle   VARCHAR(200),
  link_url   VARCHAR(255),
  sort_order INT NOT NULL DEFAULT 0,
  status     INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 学习资源升级（升级方案 3.4：补分类/封面/排序/状态；沿用既有复数表名）
-- 后台管理模块（V8）：补 tags/attachment_url；分类为 learning_category 字典表，资源存分类名
CREATE TABLE learning_resources (
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
CREATE TABLE learning_category (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(50) NOT NULL UNIQUE,
  description VARCHAR(255),
  sort_order  INT NOT NULL DEFAULT 0,
  status      INTEGER NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 能力培养条目（首页"我们培养的能力"）
CREATE TABLE service_item (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  icon        VARCHAR(50),
  sort_order  INT NOT NULL DEFAULT 0,
  status      VARCHAR(20) NOT NULL DEFAULT 'published' COMMENT 'published/offline/draft',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ==== V2: 互动表 ====
-- ============================================================
-- V2 互动表：前台用户 / 评论 / 留言
-- 注释：comment.target_type+target_id 为多态关联、无外键约束，
--       删除 work/news 时由服务层同步清理（见实施方案 §3.4）
-- ============================================================

CREATE TABLE public_user (
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
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_public_user_status ON public_user (status);

CREATE TABLE `comment` (
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
CREATE INDEX idx_comment_target ON `comment` (target_type, target_id, status);
CREATE INDEX idx_comment_user ON `comment` (user_id);

CREATE TABLE message (
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
CREATE INDEX idx_message_status_time ON message (status, created_at);

-- ==== V3: RBAC 与系统表 ====
-- ============================================================
-- V3 RBAC 与系统表：管理员 / 角色 / 权限 / 站点设置 / 上传文件 / 审计日志 / 备份
-- ============================================================

CREATE TABLE sys_user (
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

CREATE TABLE sys_role (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  code        VARCHAR(50) NOT NULL UNIQUE COMMENT 'SUPER_ADMIN/EDITOR/OPERATOR/AUDITOR',
  name        VARCHAR(50) NOT NULL,
  description VARCHAR(255),
  builtin     INTEGER NOT NULL DEFAULT 0 COMMENT '1=内置不可删改',
  status      INTEGER NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  code       VARCHAR(50) NOT NULL UNIQUE,
  name       VARCHAR(50) NOT NULL,
  module     VARCHAR(50),
  type       VARCHAR(20) NOT NULL DEFAULT 'BUTTON' COMMENT 'MENU/BUTTON',
  sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
);

CREATE TABLE sys_role_permission (
  role_id       BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_rp_perm FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
);

CREATE TABLE site_settings (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  group_name    VARCHAR(50) NOT NULL COMMENT 'basic/contact/social/seo/notify/content',
  setting_key   VARCHAR(100) NOT NULL UNIQUE,
  setting_value TEXT,
  description   VARCHAR(255),
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE upload_file (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  file_name   VARCHAR(255) NOT NULL COMMENT '原始文件名',
  stored_path VARCHAR(255) NOT NULL COMMENT '相对存储路径',
  url         VARCHAR(255) NOT NULL,
  size        BIGINT NOT NULL,
  mime_type   VARCHAR(100),
  uploaded_by BIGINT,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_operation_log (
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
CREATE INDEX idx_operation_log_time ON sys_operation_log (created_at);
CREATE INDEX idx_operation_log_user ON sys_operation_log (username);

CREATE TABLE backup_record (
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

CREATE TABLE visit_log (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  page_path  VARCHAR(255) NOT NULL,
  ip_hash    VARCHAR(64) NOT NULL COMMENT 'SHA-256(ip)',
  ua         VARCHAR(255),
  referer    VARCHAR(500),
  visited_at DATETIME NOT NULL
);
CREATE INDEX idx_visit_log_time ON visit_log (visited_at);
CREATE INDEX idx_visit_log_ip ON visit_log (ip_hash, visited_at);

CREATE TABLE stats_daily (
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

-- ==== V6: 种子内容 ====
-- ============================================================
-- V6 种子：站点内容（原 data.sql 数据全量迁移 + 新增示例）
-- data.sql 已停用（升级方案 P1-1），此处保证全量执行后首页内容完整。
-- ============================================================

-- 1. 作品（原 data.sql 4 条原样迁移）
INSERT INTO work (slug, title, category, summary, content, external_link, featured, sort_order, status, view_count, icon, cover_class, tag_type, authors, date_label, created_at, updated_at) VALUES
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

-- 2. 能力培养条目（原 data.sql 3 条原样迁移）
INSERT INTO service_item (title, description, icon, sort_order, status, created_at, updated_at) VALUES
('AI智能应用开发', '使用前沿技术栈，构建高性能、响应式的智能化应用。', '🌐', 1, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('大模型学术论文研究', '聚焦大语言模型前沿方向，开展学术论文撰写与发表。', '🔬', 2, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('Agent开发', '基于大模型开发智能体，支持自主规划、工具调用与任务落地。', '🤖', 3, 'published', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 3. 学习资源（原 data.sql 4 条，按升级方案 3.4 补分类/排序/状态；补正文 content 富文本）
INSERT INTO learning_resources (title, description, content, url, category, sort_order, status, created_at, updated_at) VALUES
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
INSERT INTO news (slug, title, category, summary, content, status, sort_order, published_at, created_at, updated_at) VALUES
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
INSERT INTO carousel (image_url, title, subtitle, sort_order, status, created_at, updated_at) VALUES
('/uploads/placeholder-1.jpg', '独角兽工作室', '培养面向未来的工程人才', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('/uploads/placeholder-2.jpg', '2026 招新进行中', '加入我们，一起创造', 2, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ==== V7: SQL 监控 ====
-- SQL 监控权限码（Druid 连接池统计，页面 /admin/sql-monitor）
-- 不插角色映射行：SUPER_ADMIN 由 AdminPermissionService 代码级全量加载，新增码自动生效
INSERT INTO sys_permission (code, name, module, type, sort_order) VALUES
('system:sql', 'SQL 监控', '系统', 'MENU', 93);

-- ==== V8: 学习资料管理 ====
-- 学习资料管理权限码（页面 /admin/learning 与 /admin/learning/categories）
INSERT INTO sys_permission (code, name, module, type, sort_order) VALUES
('learning:list',     '学习资料列表', '学习资料', 'MENU', 94),
('learning:create',   '新增学习资料', '学习资料', 'BUTTON', 95),
('learning:edit',     '编辑学习资料', '学习资料', 'BUTTON', 96),
('learning:delete',   '删除学习资料', '学习资料', 'BUTTON', 97),
('learning:publish',  '上下架资料',   '学习资料', 'BUTTON', 98),
('learning:category', '资料分类管理', '学习资料', 'MENU', 99);

-- 内容小编（EDITOR）同步获得学习资料管理权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.code = 'EDITOR' AND p.code IN (
  'learning:list','learning:create','learning:edit','learning:delete','learning:publish','learning:category'
);
