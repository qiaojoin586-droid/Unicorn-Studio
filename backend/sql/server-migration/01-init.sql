-- Unicorn Studio / MySQL 8.0.19+ / utf8mb4_0900_ai_ci
-- 基础结构来源：2026-11-13 只读核对 MySQL 8.0.42；新增缓存版本表为代码定义。当前共 26 表 / 249 列 / 70 索引 / 4 外键。
-- 本文件仅为部署代码；整理时未执行任何 DDL/DML，未读取本地业务记录。
-- 系统默认值来自仓库 SQL，不包含数据库中的用户、内容、密码或 API Key。
-- 使用 run-sql.sh 或 mysql 批处理标准输入执行；不要加 --force，也不要在交互窗口 SOURCE。
-- 批处理遇到 ERROR 必须停止；交互 SOURCE 可能继续后续语句，不适用于本文件的失败保护。
-- DDL 会隐式提交；已有数据库升级前请先备份，事务不能替代备份。
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET @us_original_sql_mode := @@SESSION.sql_mode;
SET SESSION sql_mode = TRIM(BOTH ',' FROM REPLACE(CONCAT(',', @@SESSION.sql_mode, ','), ',NO_BACKSLASH_ESCAPES,', ','));
SET @us_original_group_concat_max_len := @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = CAST(GREATEST(@@SESSION.group_concat_max_len, 8192) AS UNSIGNED);

-- 01：只用于不存在或尚无表的数据库；已有业务库使用 02-upgrade.sql。
CREATE DATABASE IF NOT EXISTS `unicorn_studio` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `unicorn_studio`;
SET @us_sql := IF((NOT EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE())), 'DO 0', 'SELECT `INIT_REQUIRES_EMPTY_DATABASE_USE_02_UPGRADE_SQL`');
PREPARE us_stmt FROM @us_sql;
EXECUTE us_stmt;
DEALLOCATE PREPARE us_stmt;

-- ai_conversation
CREATE TABLE `ai_conversation` (
  `id` varchar(26) NOT NULL COMMENT 'ULID（有序、不可猜）',
  `user_id` bigint NOT NULL COMMENT 'public_user.id；R6 决定不会为 NULL',
  `title` varchar(60) NOT NULL COMMENT '取首条用户消息，超长截断加省略号',
  `message_count` int NOT NULL DEFAULT '0' COMMENT '含被删除的消息（用于列表展示与统计）',
  `last_active_at` datetime NOT NULL COMMENT '最后一条消息时间；列表排序依据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_conv_user_active` (`user_id`,`last_active_at` DESC),
  KEY `idx_ai_conv_active` (`last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ai_message
CREATE TABLE `ai_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(26) NOT NULL COMMENT 'ai_conversation.id',
  `user_id` bigint NOT NULL COMMENT '冗余存储：所有查询都要校验归属，避免 JOIN',
  `role` varchar(16) NOT NULL COMMENT 'user / assistant',
  `content` mediumtext NOT NULL COMMENT '消息正文（assistant 可为空串）',
  `status` varchar(16) NOT NULL DEFAULT 'completed' COMMENT 'completed/interrupted/empty/blocked',
  `client_msg_id` varchar(40) DEFAULT NULL COMMENT '前端生成，用于幂等去重（仅 user 消息有值）',
  `blocked_word` varchar(64) DEFAULT NULL COMMENT '命中敏感词时记录原词（合规复核用）',
  `token_usage` int DEFAULT NULL COMMENT 'assistant 消息的 token 用量；流式可能拿不到，允许 NULL',
  `context_eligible` tinyint NOT NULL DEFAULT '1' COMMENT '是否参与记忆模块的上下文（工具消息=0）',
  `deleted_at` datetime DEFAULT NULL COMMENT '用户软删除标记（R11）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_msg_client` (`conversation_id`,`client_msg_id`),
  KEY `idx_ai_msg_session` (`conversation_id`,`id`),
  KEY `idx_ai_msg_user_created` (`user_id`,`created_at`),
  KEY `idx_ai_msg_created` (`created_at`),
  KEY `idx_ai_msg_blocked` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ai_tool_config
CREATE TABLE `ai_tool_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tool_name` varchar(64) NOT NULL COMMENT '模型看到的工具名（全局唯一）',
  `tool_type` varchar(16) NOT NULL COMMENT 'builtin / api / mcp',
  `display_name` varchar(64) NOT NULL,
  `description` varchar(500) NOT NULL COMMENT '【模型路由依据】模型据此判断何时调用，必填',
  `http_method` varchar(8) DEFAULT NULL COMMENT 'api 类型：GET/POST',
  `http_url` varchar(500) DEFAULT NULL COMMENT 'api 类型：目标地址（受 §11.4 SSRF 防护约束）',
  `http_headers` varchar(1000) DEFAULT NULL COMMENT 'api 类型：JSON；敏感值加密存储',
  `http_body_tmpl` varchar(1000) DEFAULT NULL COMMENT 'api 类型：请求体模板',
  `param_schema` varchar(2000) DEFAULT NULL COMMENT 'JSON Schema（P2 改造后由工具声明或后台填写）',
  `mcp_server_id` bigint DEFAULT NULL COMMENT 'mcp 类型（§11.3 二期才建 ai_mcp_server，本期恒为 NULL）',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '0禁用 1启用；新行默认禁用（保守）。内置工具同步入库时置 1——其描述来自代码、无被外部篡改的可能',
  `scope` varchar(16) NOT NULL DEFAULT 'admin' COMMENT '可见范围三态：public=内置只读工具，前台可装配；admin=仅后台管理员；mcp=恒仅后台',
  `priority` int NOT NULL DEFAULT '100' COMMENT '越小越优先；用于单轮装配上限的取舍',
  `timeout_ms` int NOT NULL DEFAULT '5000' COMMENT '单工具超时（R18 默认 5s）',
  `fingerprint` char(64) NOT NULL COMMENT '名称+描述+参数Schema 的 SHA-256，用于变更需确认',
  `confirmed_at` datetime DEFAULT NULL COMMENT '管理员确认当前 fingerprint 的时间；内置工具直接写 NOW()',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_tool_name` (`tool_name`),
  KEY `idx_ai_tool_scope_enabled` (`scope`,`enabled`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ai_tool_invocation
CREATE TABLE `ai_tool_invocation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(26) DEFAULT NULL COMMENT 'ai_conversation.id；后台助手为 NULL',
  `message_id` bigint DEFAULT NULL COMMENT '关联的 assistant 消息。流式下写入时必然为 NULL，⑧ 落库后回填',
  `invoker_type` varchar(16) NOT NULL COMMENT 'site_user / admin；配额与越权排查都依赖它',
  `invoker_id` bigint NOT NULL COMMENT 'public_user.id 或 sys_user.id',
  `tool_name` varchar(64) NOT NULL,
  `args_digest` varchar(500) DEFAULT NULL COMMENT '参数摘要（截断 500 字符 + 脱敏），绝不存全文',
  `result_status` varchar(16) NOT NULL COMMENT 'ok / empty / invalid_argument / timeout / upstream_error / forbidden',
  `result_bytes` int DEFAULT NULL COMMENT '返回体大小（用于观测大返回值）',
  `duration_ms` int NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_tool_inv_user` (`invoker_id`,`created_at`),
  KEY `idx_ai_tool_inv_name` (`tool_name`,`created_at`),
  KEY `idx_ai_tool_inv_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- backup_record
CREATE TABLE `backup_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) DEFAULT NULL,
  `file_size` bigint DEFAULT NULL,
  `type` varchar(20) NOT NULL COMMENT 'manual/scheduled',
  `status` varchar(20) NOT NULL COMMENT 'running/success/failed',
  `error_msg` varchar(500) DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_backup_record_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- cache_revision
CREATE TABLE `cache_revision` (
  `cache_domain` varchar(32) NOT NULL,
  `epoch` char(36) NOT NULL,
  `revision` bigint unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`cache_domain`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- capability_item
CREATE TABLE `capability_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL,
  `description` varchar(500) NOT NULL,
  `icon` varchar(50) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'published' COMMENT 'published/offline',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_capability_status_sort` (`status`,`sort_order`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- carousel
CREATE TABLE `carousel` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `image_url` varchar(255) NOT NULL,
  `title` varchar(100) DEFAULT NULL,
  `subtitle` varchar(200) DEFAULT NULL,
  `link_url` varchar(255) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `page_key` varchar(32) NOT NULL DEFAULT 'home' COMMENT '绑定页面：home/about/work-detail',
  `media_type` varchar(16) NOT NULL DEFAULT 'image' COMMENT 'image=静态壁纸 / video=动态壁纸',
  `video_url` varchar(255) DEFAULT NULL COMMENT '动态壁纸视频地址，仅 media_type=video 时必填',
  PRIMARY KEY (`id`),
  KEY `idx_carousel_page_status_sort` (`page_key`,`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- comment
CREATE TABLE `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `target_type` varchar(20) NOT NULL COMMENT 'work/news',
  `target_id` bigint NOT NULL,
  `user_id` bigint DEFAULT NULL COMMENT 'public_user.id；NULL=游客',
  `guest_nickname` varchar(50) DEFAULT NULL,
  `parent_id` bigint DEFAULT NULL COMMENT '回复的评论ID（楼中楼）',
  `content` varchar(1000) NOT NULL COMMENT '纯文本',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected',
  `ip_hash` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_comment_target` (`target_type`,`target_id`,`status`),
  KEY `idx_comment_user` (`user_id`),
  KEY `idx_comment_parent` (`parent_id`),
  KEY `idx_comment_root_order` (`target_type`,`target_id`,`status`,`parent_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- learning_category
CREATE TABLE `learning_category` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1' COMMENT '1鍚?敤 0鍋滅敤',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- learning_resources
CREATE TABLE `learning_resources` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL,
  `description` varchar(500) NOT NULL,
  `content` mediumtext COMMENT '富文本，保存前已消毒',
  `url` varchar(255) DEFAULT NULL,
  `category` varchar(50) DEFAULT NULL,
  `tags` varchar(255) DEFAULT NULL COMMENT '閫楀彿鍒嗛殧鏍囩?',
  `cover_url` varchar(255) DEFAULT NULL,
  `attachment_url` varchar(255) DEFAULT NULL COMMENT '闄勪欢锛堜笂浼犳枃浠?URL锛',
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_learning_status_sort` (`status`,`sort_order`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- message
CREATE TABLE `message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `contact` varchar(100) DEFAULT NULL,
  `content` varchar(2000) NOT NULL,
  `source` varchar(20) NOT NULL DEFAULT 'contact_form' COMMENT 'contact_form/guestbook',
  `status` varchar(20) NOT NULL DEFAULT 'new' COMMENT 'new/replied/closed',
  `reply_content` text,
  `replied_by` bigint DEFAULT NULL,
  `replied_at` datetime DEFAULT NULL,
  `ip_hash` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_message_status_time` (`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- news
CREATE TABLE `news` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slug` varchar(100) NOT NULL,
  `title` varchar(200) NOT NULL,
  `category` varchar(50) DEFAULT NULL,
  `cover_url` varchar(255) DEFAULT NULL,
  `summary` varchar(500) DEFAULT NULL,
  `content` mediumtext,
  `author_id` bigint DEFAULT NULL COMMENT 'sys_user.id',
  `featured` int NOT NULL DEFAULT '0',
  `sort_order` int NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/offline',
  `view_count` int NOT NULL DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `slug` (`slug`),
  KEY `idx_news_status_sort` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- public_user
CREATE TABLE `public_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `nickname` varchar(50) NOT NULL,
  `email` varchar(100) DEFAULT NULL,
  `password` varchar(100) NOT NULL COMMENT 'BCrypt',
  `avatar` varchar(255) DEFAULT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '1正常 0禁言 2注销',
  `ban_reason` varchar(255) DEFAULT NULL,
  `ban_until` datetime DEFAULT NULL COMMENT 'NULL=永久禁言',
  `register_ip` varchar(45) DEFAULT NULL,
  `last_login_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ai_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '1允许使用AI助手 0禁止（独立于禁言 status）',
  `phone` varchar(11) DEFAULT NULL COMMENT '主站登录手机号，旧账号待绑定',
  `auth_version` bigint NOT NULL DEFAULT '0' COMMENT '主站登录凭据版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`),
  UNIQUE KEY `uk_public_user_phone` (`phone`),
  KEY `idx_public_user_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- service_item
CREATE TABLE `service_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(100) NOT NULL,
  `description` varchar(500) NOT NULL,
  `icon` varchar(50) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'published' COMMENT 'published/offline/draft',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_service_item_status_sort` (`status`,`sort_order`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- site_settings
CREATE TABLE `site_settings` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_name` varchar(50) NOT NULL COMMENT 'basic/contact/social/seo/notify/content',
  `setting_key` varchar(100) NOT NULL,
  `setting_value` text,
  `description` varchar(255) DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `setting_key` (`setting_key`),
  KEY `idx_site_settings_group` (`group_name`,`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- stats_daily
CREATE TABLE `stats_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `stat_date` date NOT NULL,
  `pv` int NOT NULL DEFAULT '0',
  `uv` int NOT NULL DEFAULT '0',
  `new_users` int NOT NULL DEFAULT '0',
  `new_messages` int NOT NULL DEFAULT '0',
  `new_comments` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_operation_log
CREATE TABLE `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint DEFAULT NULL,
  `username` varchar(50) DEFAULT NULL,
  `module` varchar(50) DEFAULT NULL,
  `action` varchar(50) DEFAULT NULL,
  `method` varchar(10) DEFAULT NULL,
  `path` varchar(255) DEFAULT NULL,
  `params` text COMMENT '已脱敏',
  `ip` varchar(45) DEFAULT NULL,
  `status` varchar(20) NOT NULL COMMENT 'success/failed',
  `error_msg` varchar(500) DEFAULT NULL,
  `duration_ms` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_operation_log_time` (`created_at`),
  KEY `idx_operation_log_user` (`username`),
  KEY `idx_operation_log_module_status` (`module`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_permission
CREATE TABLE `sys_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL,
  `name` varchar(50) NOT NULL,
  `module` varchar(50) DEFAULT NULL,
  `type` varchar(20) NOT NULL DEFAULT 'BUTTON' COMMENT 'MENU/BUTTON',
  `sort_order` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_role
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(50) NOT NULL COMMENT 'SUPER_ADMIN/EDITOR/OPERATOR/AUDITOR',
  `name` varchar(50) NOT NULL,
  `description` varchar(255) DEFAULT NULL,
  `builtin` int NOT NULL DEFAULT '0' COMMENT '1=内置不可删改',
  `status` int NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_user
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL,
  `password` varchar(100) NOT NULL COMMENT 'BCrypt',
  `nickname` varchar(50) DEFAULT NULL,
  `avatar` varchar(255) DEFAULT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '1启用 0禁用',
  `must_change_password` int NOT NULL DEFAULT '0' COMMENT '首次登录强制改密',
  `refresh_token` varchar(500) DEFAULT NULL COMMENT '当前存 UUID；留长以兼容未来 JWT 格式',
  `refresh_token_expires_at` datetime DEFAULT NULL,
  `failed_login_attempts` int NOT NULL DEFAULT '0' COMMENT '连续失败次数，登录成功/解锁后清零',
  `locked_until` datetime DEFAULT NULL COMMENT 'NULL=未锁定；账号锁定持久化，重启/多实例不丢',
  `last_login_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`),
  KEY `idx_sys_user_refresh_token` (`refresh_token`(64))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- upload_file
CREATE TABLE `upload_file` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `file_name` varchar(255) NOT NULL COMMENT '原始文件名',
  `stored_path` varchar(255) NOT NULL COMMENT '相对存储路径',
  `url` varchar(255) NOT NULL,
  `size` bigint NOT NULL,
  `mime_type` varchar(100) DEFAULT NULL,
  `uploaded_by` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- visit_log
CREATE TABLE `visit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `page_path` varchar(255) NOT NULL,
  `ip_hash` varchar(64) NOT NULL COMMENT 'SHA-256(ip)',
  `ua` varchar(255) DEFAULT NULL,
  `referer` varchar(500) DEFAULT NULL,
  `visited_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_visit_log_time` (`visited_at`),
  KEY `idx_visit_log_ip` (`ip_hash`,`visited_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- work
CREATE TABLE `work` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slug` varchar(100) NOT NULL,
  `title` varchar(200) NOT NULL,
  `category` varchar(50) DEFAULT NULL,
  `cover_url` varchar(255) DEFAULT NULL,
  `summary` varchar(500) DEFAULT NULL,
  `content` mediumtext COMMENT '富文本，保存前已消毒',
  `client_name` varchar(100) DEFAULT NULL,
  `external_link` varchar(255) DEFAULT NULL,
  `featured` int NOT NULL DEFAULT '0',
  `sort_order` int NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'draft' COMMENT 'draft/published/offline',
  `view_count` int NOT NULL DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  `icon` varchar(50) DEFAULT NULL,
  `cover_class` varchar(100) DEFAULT NULL,
  `tag_type` varchar(20) DEFAULT NULL COMMENT 'award/paper',
  `authors` varchar(100) DEFAULT NULL,
  `date_label` varchar(20) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `slug` (`slug`),
  KEY `idx_work_status_sort` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_role_permission
CREATE TABLE `sys_role_permission` (
  `role_id` bigint NOT NULL,
  `permission_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `fk_rp_perm` (`permission_id`),
  CONSTRAINT `fk_rp_perm` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`),
  CONSTRAINT `fk_rp_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- sys_user_role
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `fk_ur_role` (`role_id`),
  CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`),
  CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 内置结构核对：仅核对预期对象，保留线上额外表、额外字段及额外索引。
SET @us_column_report := (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('table', table_name, 'column', column_name, 'expected_type', expected_type, 'actual_type', actual_type, 'expected_nullable', expected_nullable, 'actual_nullable', actual_nullable, 'expected_default', expected_default, 'actual_default', actual_default, 'expected_extra', expected_extra, 'actual_extra', actual_extra, 'expected_collation', expected_collation, 'actual_collation', actual_collation)), JSON_ARRAY()) FROM (
SELECT e.`table` AS table_name, e.`name` AS column_name, e.`type` AS expected_type, c.COLUMN_TYPE AS actual_type, e.`nullable` AS expected_nullable, c.IS_NULLABLE AS actual_nullable, e.`default` AS expected_default, c.COLUMN_DEFAULT AS actual_default, e.`extra` AS expected_extra, c.EXTRA AS actual_extra, e.`collation` AS expected_collation, c.COLLATION_NAME AS actual_collation FROM (
SELECT 'ai_conversation' AS `table`, 'id' AS `name`, 'varchar(26)' AS `type`, 'NO' AS `nullable`, NULL AS `default`, '' AS `extra`, 'utf8mb4' AS `charset`, 'utf8mb4_0900_ai_ci' AS `collation`, '' AS `generated`
UNION ALL
SELECT 'ai_conversation', 'user_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_conversation', 'title', 'varchar(60)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_conversation', 'message_count', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'ai_conversation', 'last_active_at', 'datetime', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_conversation', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'ai_conversation', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED on update CURRENT_TIMESTAMP', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'conversation_id', 'varchar(26)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'user_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'role', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'content', 'mediumtext', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'status', 'varchar(16)', 'NO', 'completed', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'client_msg_id', 'varchar(40)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'blocked_word', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_message', 'token_usage', 'int', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'context_eligible', 'tinyint', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'deleted_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_message', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'tool_name', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'tool_type', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'display_name', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'description', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'http_method', 'varchar(8)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'http_url', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'http_headers', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'http_body_tmpl', 'varchar(1000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'param_schema', 'varchar(2000)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'mcp_server_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'enabled', 'tinyint', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'scope', 'varchar(16)', 'NO', 'admin', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'priority', 'int', 'NO', '100', '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'timeout_ms', 'int', 'NO', '5000', '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'fingerprint', 'char(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_config', 'confirmed_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_config', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED on update CURRENT_TIMESTAMP', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'conversation_id', 'varchar(26)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_invocation', 'message_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'invoker_type', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_invocation', 'invoker_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'tool_name', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_invocation', 'args_digest', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_invocation', 'result_status', 'varchar(16)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'ai_tool_invocation', 'result_bytes', 'int', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'duration_ms', 'int', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'ai_tool_invocation', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'backup_record', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'backup_record', 'file_name', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'backup_record', 'file_size', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'backup_record', 'type', 'varchar(20)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'backup_record', 'status', 'varchar(20)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'backup_record', 'error_msg', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'backup_record', 'created_by', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'backup_record', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'capability_item', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'capability_item', 'title', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'capability_item', 'description', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'capability_item', 'icon', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'capability_item', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'capability_item', 'status', 'varchar(20)', 'NO', 'published', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'capability_item', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'capability_item', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'image_url', 'varchar(255)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'title', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'subtitle', 'varchar(200)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'link_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'carousel', 'page_key', 'varchar(32)', 'NO', 'home', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'media_type', 'varchar(16)', 'NO', 'image', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'carousel', 'video_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'comment', 'target_type', 'varchar(20)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'target_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'comment', 'user_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'comment', 'guest_nickname', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'parent_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'comment', 'content', 'varchar(1000)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'status', 'varchar(20)', 'NO', 'pending', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'ip_hash', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'comment', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'learning_category', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'learning_category', 'name', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_category', 'description', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_category', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'learning_category', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'learning_category', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'learning_resources', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'learning_resources', 'title', 'varchar(200)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'description', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'content', 'mediumtext', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'category', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'tags', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'cover_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'attachment_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'learning_resources', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'learning_resources', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'learning_resources', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'learning_resources', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'message', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'message', 'name', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'contact', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'content', 'varchar(2000)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'source', 'varchar(20)', 'NO', 'contact_form', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'status', 'varchar(20)', 'NO', 'new', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'reply_content', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'replied_by', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'message', 'replied_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'message', 'ip_hash', 'varchar(64)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'message', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'news', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'news', 'slug', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'title', 'varchar(200)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'category', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'cover_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'summary', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'content', 'mediumtext', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'author_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'news', 'featured', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'news', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'news', 'status', 'varchar(20)', 'NO', 'draft', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'news', 'view_count', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'news', 'published_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'news', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'news', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'nickname', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'email', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'password', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'avatar', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'ban_reason', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'ban_until', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'register_ip', 'varchar(45)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'last_login_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'ai_enabled', 'tinyint', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'public_user', 'phone', 'varchar(11)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'public_user', 'auth_version', 'bigint', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'service_item', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'service_item', 'title', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'service_item', 'description', 'varchar(500)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'service_item', 'icon', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'service_item', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'service_item', 'status', 'varchar(20)', 'NO', 'published', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'service_item', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'service_item', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'site_settings', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'site_settings', 'group_name', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'site_settings', 'setting_key', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'site_settings', 'setting_value', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'site_settings', 'description', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'site_settings', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'stat_date', 'date', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'pv', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'uv', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'new_users', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'new_messages', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'stats_daily', 'new_comments', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_operation_log', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'sys_operation_log', 'user_id', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_operation_log', 'username', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'module', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'action', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'method', 'varchar(10)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'path', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'params', 'text', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'ip', 'varchar(45)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'status', 'varchar(20)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'error_msg', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_operation_log', 'duration_ms', 'int', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_operation_log', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'sys_permission', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'sys_permission', 'code', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_permission', 'name', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_permission', 'module', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_permission', 'type', 'varchar(20)', 'NO', 'BUTTON', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_permission', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_role', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'sys_role', 'code', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_role', 'name', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_role', 'description', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_role', 'builtin', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_role', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_role', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'sys_role_permission', 'role_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_role_permission', 'permission_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'username', 'varchar(50)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_user', 'password', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_user', 'nickname', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_user', 'avatar', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_user', 'status', 'int', 'NO', '1', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'must_change_password', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'refresh_token', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'sys_user', 'refresh_token_expires_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'failed_login_attempts', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'locked_until', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'last_login_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'sys_user', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'sys_user_role', 'user_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'sys_user_role', 'role_id', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'upload_file', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'upload_file', 'file_name', 'varchar(255)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'upload_file', 'stored_path', 'varchar(255)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'upload_file', 'url', 'varchar(255)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'upload_file', 'size', 'bigint', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'upload_file', 'mime_type', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'upload_file', 'uploaded_by', 'bigint', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'upload_file', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'visit_log', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'visit_log', 'page_path', 'varchar(255)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'visit_log', 'ip_hash', 'varchar(64)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'visit_log', 'ua', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'visit_log', 'referer', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'visit_log', 'visited_at', 'datetime', 'NO', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'work', 'id', 'bigint', 'NO', NULL, 'auto_increment', NULL, NULL, ''
UNION ALL
SELECT 'work', 'slug', 'varchar(100)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'title', 'varchar(200)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'category', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'cover_url', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'summary', 'varchar(500)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'content', 'mediumtext', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'client_name', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'external_link', 'varchar(255)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'featured', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'work', 'sort_order', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'work', 'status', 'varchar(20)', 'NO', 'draft', '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'view_count', 'int', 'NO', '0', '', NULL, NULL, ''
UNION ALL
SELECT 'work', 'published_at', 'datetime', 'YES', NULL, '', NULL, NULL, ''
UNION ALL
SELECT 'work', 'icon', 'varchar(50)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'cover_class', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'tag_type', 'varchar(20)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'authors', 'varchar(100)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'date_label', 'varchar(20)', 'YES', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'work', 'created_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'work', 'updated_at', 'datetime', 'NO', 'CURRENT_TIMESTAMP', 'DEFAULT_GENERATED', NULL, NULL, ''
UNION ALL
SELECT 'cache_revision', 'cache_domain', 'varchar(32)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'cache_revision', 'epoch', 'char(36)', 'NO', NULL, '', 'utf8mb4', 'utf8mb4_0900_ai_ci', ''
UNION ALL
SELECT 'cache_revision', 'revision', 'bigint unsigned', 'NO', '1', '', NULL, NULL, ''
) e LEFT JOIN information_schema.COLUMNS c ON c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = e.`table` AND c.COLUMN_NAME = e.`name` WHERE c.COLUMN_NAME IS NULL OR NOT (LOWER(c.COLUMN_TYPE) <=> e.`type`) OR NOT (c.IS_NULLABLE <=> e.`nullable`) OR NOT (c.COLUMN_DEFAULT <=> e.`default`) OR NOT (LOWER(c.EXTRA) <=> LOWER(e.`extra`)) OR NOT (c.CHARACTER_SET_NAME <=> e.`charset`) OR NOT (c.COLLATION_NAME <=> e.`collation`) OR NOT (c.GENERATION_EXPRESSION <=> e.`generated`)
) issues);
SET @us_column_issues := JSON_LENGTH(@us_column_report);
SELECT IF(@us_column_issues = 0, 'OK', 'FAIL') AS column_structure, JSON_PRETTY(@us_column_report) AS column_differences;
SET @us_table_issues := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('ai_conversation', 'ai_message', 'ai_tool_config', 'ai_tool_invocation', 'backup_record', 'cache_revision', 'capability_item', 'carousel', 'comment', 'learning_category', 'learning_resources', 'message', 'news', 'public_user', 'service_item', 'site_settings', 'stats_daily', 'sys_operation_log', 'sys_permission', 'sys_role', 'sys_role_permission', 'sys_user', 'sys_user_role', 'upload_file', 'visit_log', 'work') AND (ENGINE <> 'InnoDB' OR TABLE_COLLATION <> 'utf8mb4_0900_ai_ci'));
SET @us_table_count := (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('ai_conversation', 'ai_message', 'ai_tool_config', 'ai_tool_invocation', 'backup_record', 'cache_revision', 'capability_item', 'carousel', 'comment', 'learning_category', 'learning_resources', 'message', 'news', 'public_user', 'service_item', 'site_settings', 'stats_daily', 'sys_operation_log', 'sys_permission', 'sys_role', 'sys_role_permission', 'sys_user', 'sys_user_role', 'upload_file', 'visit_log', 'work') AND TABLE_TYPE = 'BASE TABLE');
SET @us_object_report := (SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('object', object_name, 'result', result)), JSON_ARRAY()) FROM (
SELECT 'ai_conversation.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_conversation.idx_ai_conv_active' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'last_active_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_conversation.idx_ai_conv_user_active' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'user_id,last_active_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.idx_ai_msg_blocked' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.idx_ai_msg_created' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.idx_ai_msg_session' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'conversation_id,id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.idx_ai_msg_user_created' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'user_id,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_message.uk_ai_msg_client' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'conversation_id,client_msg_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_config.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_config' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_config.idx_ai_tool_scope_enabled' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_config' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'scope,enabled,priority'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_config.uk_ai_tool_name' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_config' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'tool_name'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_invocation.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_invocation' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_invocation.idx_ai_tool_inv_created' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_invocation' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_invocation.idx_ai_tool_inv_name' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_invocation' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'tool_name,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'ai_tool_invocation.idx_ai_tool_inv_user' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_tool_invocation' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'invoker_id,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'backup_record.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_record' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'backup_record.idx_backup_record_created' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'backup_record' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'cache_revision.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cache_revision' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'cache_domain'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'capability_item.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_item' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'capability_item.idx_capability_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capability_item' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,sort_order,id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'carousel.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'carousel.idx_carousel_page_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'carousel' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'page_key,status,sort_order'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'comment.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'comment.idx_comment_parent' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'parent_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'comment.idx_comment_root_order' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'target_type,target_id,status,parent_id,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'comment.idx_comment_target' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'target_type,target_id,status'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'comment.idx_comment_user' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comment' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'user_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'learning_category.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_category' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'learning_category.name' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_category' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'name'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'learning_resources.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_resources' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'learning_resources.idx_learning_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'learning_resources' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,sort_order,id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'message.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'message.idx_message_status_time' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'news.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'news.idx_news_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,sort_order'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'news.slug' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'news' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'slug'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'public_user.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'public_user.email' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'email'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'public_user.idx_public_user_status' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'public_user.uk_public_user_phone' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_user' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'phone'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'service_item.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'service_item' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'service_item.idx_service_item_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'service_item' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,sort_order,id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'site_settings.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_settings' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'site_settings.idx_site_settings_group' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_settings' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'group_name,setting_key'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'site_settings.setting_key' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'site_settings' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'setting_key'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'stats_daily.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stats_daily' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'stats_daily.stat_date' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stats_daily' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'stat_date'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_operation_log.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_operation_log.idx_operation_log_module_status' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'module,status'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_operation_log.idx_operation_log_time' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'created_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_operation_log.idx_operation_log_user' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_operation_log' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'username'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_permission.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_permission.code' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_permission' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'code'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_role.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_role.code' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'code'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_role_permission.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_permission' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'role_id,permission_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_role_permission.fk_rp_perm' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_permission' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'permission_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_user.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_user.idx_sys_user_refresh_token' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'refresh_token(64)'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_user.username' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'username'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_user_role.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user_role' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'user_id,role_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_user_role.fk_ur_role' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user_role' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'role_id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'upload_file.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'upload_file' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'visit_log.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'visit_log' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'visit_log.idx_visit_log_ip' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'visit_log' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'ip_hash,visited_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'visit_log.idx_visit_log_time' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'visit_log' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'visited_at'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'work.PRIMARY' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work' AND INDEX_NAME = 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'work.idx_work_status_sort' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 1 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'status,sort_order'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'work.slug' AS object_name, IF(EXISTS (SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work' AND INDEX_NAME <> 'PRIMARY' GROUP BY INDEX_NAME HAVING MIN(NON_UNIQUE) = 0 AND MIN(INDEX_TYPE) = 'BTREE' AND MIN(IS_VISIBLE = 'YES') = 1 AND GROUP_CONCAT(CONCAT(COLUMN_NAME, IF(SUB_PART IS NULL, '', CONCAT('(', SUB_PART, ')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'slug'), 'OK', 'FAIL') AS result
UNION ALL
SELECT 'sys_role_permission.fk_rp_perm', IF(EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'sys_role_permission' AND k.REFERENCED_TABLE_NAME = 'sys_permission' AND k.REFERENCED_TABLE_SCHEMA = DATABASE() GROUP BY k.CONSTRAINT_NAME HAVING REPLACE(MIN(r.UPDATE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND REPLACE(MIN(r.DELETE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'permission_id' AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'id'), 'OK', 'FAIL')
UNION ALL
SELECT 'sys_role_permission.fk_rp_role', IF(EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'sys_role_permission' AND k.REFERENCED_TABLE_NAME = 'sys_role' AND k.REFERENCED_TABLE_SCHEMA = DATABASE() GROUP BY k.CONSTRAINT_NAME HAVING REPLACE(MIN(r.UPDATE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND REPLACE(MIN(r.DELETE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'role_id' AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'id'), 'OK', 'FAIL')
UNION ALL
SELECT 'sys_user_role.fk_ur_role', IF(EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'sys_user_role' AND k.REFERENCED_TABLE_NAME = 'sys_role' AND k.REFERENCED_TABLE_SCHEMA = DATABASE() GROUP BY k.CONSTRAINT_NAME HAVING REPLACE(MIN(r.UPDATE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND REPLACE(MIN(r.DELETE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'role_id' AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'id'), 'OK', 'FAIL')
UNION ALL
SELECT 'sys_user_role.fk_ur_user', IF(EXISTS (SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.TABLE_NAME = k.TABLE_NAME AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = 'sys_user_role' AND k.REFERENCED_TABLE_NAME = 'sys_user' AND k.REFERENCED_TABLE_SCHEMA = DATABASE() GROUP BY k.CONSTRAINT_NAME HAVING REPLACE(MIN(r.UPDATE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND REPLACE(MIN(r.DELETE_RULE), 'NO ACTION', 'RESTRICT') = 'RESTRICT' AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'user_id' AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',') = 'id'), 'OK', 'FAIL')
) objects WHERE result = 'FAIL');
SET @us_object_issues := JSON_LENGTH(@us_object_report);
SELECT IF(@us_object_issues = 0, 'OK', 'FAIL') AS indexes_and_foreign_keys, JSON_PRETTY(@us_object_report) AS object_differences;
SELECT DATABASE() AS database_name, @us_table_count AS expected_tables_found, 249 AS expected_columns, @us_table_issues AS table_option_issues, @us_object_issues AS index_or_foreign_key_issues;
SET @us_sql := IF((@us_column_issues = 0 AND @us_table_issues = 0 AND @us_object_issues = 0 AND @us_table_count = 26), 'DO 0', 'SELECT `SCHEMA_CHECK_FAILED_REVIEW_DIFFERENCES_ABOVE`');
PREPARE us_stmt FROM @us_sql;
EXECUTE us_stmt;
DEALLOCATE PREPARE us_stmt;

-- 数据库变更监听：触发器与业务修改共用事务；只存域和版本，不复制业务数据。
INSERT IGNORE INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES
('ai-history', UUID(), 1),
('capabilities', UUID(), 1),
('carousel', UUID(), 1),
('comments', UUID(), 1),
('dashboard', UUID(), 1),
('learning', UUID(), 1),
('news', UUID(), 1),
('permissions', UUID(), 1),
('services', UUID(), 1),
('settings', UUID(), 1),
('works', UUID(), 1);
DROP TRIGGER IF EXISTS `us_cache_ai_conversation_insert`;
CREATE TRIGGER `us_cache_ai_conversation_insert` AFTER INSERT ON `ai_conversation` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_ai_conversation_update`;
CREATE TRIGGER `us_cache_ai_conversation_update` AFTER UPDATE ON `ai_conversation` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_ai_conversation_delete`;
CREATE TRIGGER `us_cache_ai_conversation_delete` AFTER DELETE ON `ai_conversation` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_ai_message_insert`;
CREATE TRIGGER `us_cache_ai_message_insert` AFTER INSERT ON `ai_message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_ai_message_update`;
CREATE TRIGGER `us_cache_ai_message_update` AFTER UPDATE ON `ai_message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_ai_message_delete`;
CREATE TRIGGER `us_cache_ai_message_delete` AFTER DELETE ON `ai_message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('ai-history', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_capability_item_insert`;
CREATE TRIGGER `us_cache_capability_item_insert` AFTER INSERT ON `capability_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('capabilities', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_capability_item_update`;
CREATE TRIGGER `us_cache_capability_item_update` AFTER UPDATE ON `capability_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('capabilities', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_capability_item_delete`;
CREATE TRIGGER `us_cache_capability_item_delete` AFTER DELETE ON `capability_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('capabilities', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_carousel_insert`;
CREATE TRIGGER `us_cache_carousel_insert` AFTER INSERT ON `carousel` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('carousel', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_carousel_update`;
CREATE TRIGGER `us_cache_carousel_update` AFTER UPDATE ON `carousel` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('carousel', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_carousel_delete`;
CREATE TRIGGER `us_cache_carousel_delete` AFTER DELETE ON `carousel` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('carousel', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_comment_insert`;
CREATE TRIGGER `us_cache_comment_insert` AFTER INSERT ON `comment` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_comment_update`;
CREATE TRIGGER `us_cache_comment_update` AFTER UPDATE ON `comment` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_comment_delete`;
CREATE TRIGGER `us_cache_comment_delete` AFTER DELETE ON `comment` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_category_insert`;
CREATE TRIGGER `us_cache_learning_category_insert` AFTER INSERT ON `learning_category` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_category_update`;
CREATE TRIGGER `us_cache_learning_category_update` AFTER UPDATE ON `learning_category` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_category_delete`;
CREATE TRIGGER `us_cache_learning_category_delete` AFTER DELETE ON `learning_category` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_resources_insert`;
CREATE TRIGGER `us_cache_learning_resources_insert` AFTER INSERT ON `learning_resources` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_resources_update`;
CREATE TRIGGER `us_cache_learning_resources_update` AFTER UPDATE ON `learning_resources` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_learning_resources_delete`;
CREATE TRIGGER `us_cache_learning_resources_delete` AFTER DELETE ON `learning_resources` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('learning', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_message_insert`;
CREATE TRIGGER `us_cache_message_insert` AFTER INSERT ON `message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_message_update`;
CREATE TRIGGER `us_cache_message_update` AFTER UPDATE ON `message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_message_delete`;
CREATE TRIGGER `us_cache_message_delete` AFTER DELETE ON `message` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_news_insert`;
CREATE TRIGGER `us_cache_news_insert` AFTER INSERT ON `news` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('news', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_news_update`;
CREATE TRIGGER `us_cache_news_update` AFTER UPDATE ON `news` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('news', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_news_delete`;
CREATE TRIGGER `us_cache_news_delete` AFTER DELETE ON `news` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('news', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_public_user_insert`;
CREATE TRIGGER `us_cache_public_user_insert` AFTER INSERT ON `public_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_public_user_update`;
CREATE TRIGGER `us_cache_public_user_update` AFTER UPDATE ON `public_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_public_user_delete`;
CREATE TRIGGER `us_cache_public_user_delete` AFTER DELETE ON `public_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('comments', UUID(), 1), ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_service_item_insert`;
CREATE TRIGGER `us_cache_service_item_insert` AFTER INSERT ON `service_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('services', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_service_item_update`;
CREATE TRIGGER `us_cache_service_item_update` AFTER UPDATE ON `service_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('services', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_service_item_delete`;
CREATE TRIGGER `us_cache_service_item_delete` AFTER DELETE ON `service_item` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('services', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_site_settings_insert`;
CREATE TRIGGER `us_cache_site_settings_insert` AFTER INSERT ON `site_settings` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('settings', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_site_settings_update`;
CREATE TRIGGER `us_cache_site_settings_update` AFTER UPDATE ON `site_settings` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('settings', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_site_settings_delete`;
CREATE TRIGGER `us_cache_site_settings_delete` AFTER DELETE ON `site_settings` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('settings', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_permission_insert`;
CREATE TRIGGER `us_cache_sys_permission_insert` AFTER INSERT ON `sys_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_permission_update`;
CREATE TRIGGER `us_cache_sys_permission_update` AFTER UPDATE ON `sys_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_permission_delete`;
CREATE TRIGGER `us_cache_sys_permission_delete` AFTER DELETE ON `sys_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_insert`;
CREATE TRIGGER `us_cache_sys_role_insert` AFTER INSERT ON `sys_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_update`;
CREATE TRIGGER `us_cache_sys_role_update` AFTER UPDATE ON `sys_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_delete`;
CREATE TRIGGER `us_cache_sys_role_delete` AFTER DELETE ON `sys_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_permission_insert`;
CREATE TRIGGER `us_cache_sys_role_permission_insert` AFTER INSERT ON `sys_role_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_permission_update`;
CREATE TRIGGER `us_cache_sys_role_permission_update` AFTER UPDATE ON `sys_role_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_role_permission_delete`;
CREATE TRIGGER `us_cache_sys_role_permission_delete` AFTER DELETE ON `sys_role_permission` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_insert`;
CREATE TRIGGER `us_cache_sys_user_insert` AFTER INSERT ON `sys_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_update`;
CREATE TRIGGER `us_cache_sys_user_update` AFTER UPDATE ON `sys_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_delete`;
CREATE TRIGGER `us_cache_sys_user_delete` AFTER DELETE ON `sys_user` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_role_insert`;
CREATE TRIGGER `us_cache_sys_user_role_insert` AFTER INSERT ON `sys_user_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_role_update`;
CREATE TRIGGER `us_cache_sys_user_role_update` AFTER UPDATE ON `sys_user_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_sys_user_role_delete`;
CREATE TRIGGER `us_cache_sys_user_role_delete` AFTER DELETE ON `sys_user_role` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('permissions', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_visit_log_insert`;
CREATE TRIGGER `us_cache_visit_log_insert` AFTER INSERT ON `visit_log` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_visit_log_update`;
CREATE TRIGGER `us_cache_visit_log_update` AFTER UPDATE ON `visit_log` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_visit_log_delete`;
CREATE TRIGGER `us_cache_visit_log_delete` AFTER DELETE ON `visit_log` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_work_insert`;
CREATE TRIGGER `us_cache_work_insert` AFTER INSERT ON `work` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('works', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_work_update`;
CREATE TRIGGER `us_cache_work_update` AFTER UPDATE ON `work` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('works', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
DROP TRIGGER IF EXISTS `us_cache_work_delete`;
CREATE TRIGGER `us_cache_work_delete` AFTER DELETE ON `work` FOR EACH ROW
INSERT INTO `cache_revision` (`cache_domain`, `epoch`, `revision`) VALUES ('dashboard', UUID(), 1), ('works', UUID(), 1)
ON DUPLICATE KEY UPDATE `revision` = `revision` + 1;
UPDATE `cache_revision` SET `epoch` = UUID(), `revision` = 1;
SELECT COUNT(*) AS cache_revision_domains FROM `cache_revision`;
SET @us_sql := IF(((SELECT COUNT(*) FROM cache_revision) = 11 AND (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE() AND TRIGGER_NAME IN ('us_cache_ai_conversation_delete', 'us_cache_ai_conversation_insert', 'us_cache_ai_conversation_update', 'us_cache_ai_message_delete', 'us_cache_ai_message_insert', 'us_cache_ai_message_update', 'us_cache_capability_item_delete', 'us_cache_capability_item_insert', 'us_cache_capability_item_update', 'us_cache_carousel_delete', 'us_cache_carousel_insert', 'us_cache_carousel_update', 'us_cache_comment_delete', 'us_cache_comment_insert', 'us_cache_comment_update', 'us_cache_learning_category_delete', 'us_cache_learning_category_insert', 'us_cache_learning_category_update', 'us_cache_learning_resources_delete', 'us_cache_learning_resources_insert', 'us_cache_learning_resources_update', 'us_cache_message_delete', 'us_cache_message_insert', 'us_cache_message_update', 'us_cache_news_delete', 'us_cache_news_insert', 'us_cache_news_update', 'us_cache_public_user_delete', 'us_cache_public_user_insert', 'us_cache_public_user_update', 'us_cache_service_item_delete', 'us_cache_service_item_insert', 'us_cache_service_item_update', 'us_cache_site_settings_delete', 'us_cache_site_settings_insert', 'us_cache_site_settings_update', 'us_cache_sys_permission_delete', 'us_cache_sys_permission_insert', 'us_cache_sys_permission_update', 'us_cache_sys_role_delete', 'us_cache_sys_role_insert', 'us_cache_sys_role_permission_delete', 'us_cache_sys_role_permission_insert', 'us_cache_sys_role_permission_update', 'us_cache_sys_role_update', 'us_cache_sys_user_delete', 'us_cache_sys_user_insert', 'us_cache_sys_user_role_delete', 'us_cache_sys_user_role_insert', 'us_cache_sys_user_role_update', 'us_cache_sys_user_update', 'us_cache_visit_log_delete', 'us_cache_visit_log_insert', 'us_cache_visit_log_update', 'us_cache_work_delete', 'us_cache_work_insert', 'us_cache_work_update')) = 57), 'DO 0', 'SELECT `CACHE_CHANGE_TRACKING_CHECK_FAILED`');
PREPARE us_stmt FROM @us_sql;
EXECUTE us_stmt;
DEALLOCATE PREPARE us_stmt;

-- 系统初始化：只补仓库中已定义但目标库尚不存在的配置、权限和默认角色映射。
-- INSERT IGNORE 保留已有同键配置，不导入作品、新闻、用户等业务记录。
-- 默认授权只应用于本次新增角色或权限，不恢复已被管理员撤销的旧授权。
START TRANSACTION;
SET @us_existing_roles := (SELECT COALESCE(JSON_ARRAYAGG(code), JSON_ARRAY()) FROM sys_role);
SET @us_existing_permissions := (SELECT COALESCE(JSON_ARRAYAGG(code), JSON_ARRAY()) FROM sys_permission);
INSERT IGNORE INTO `sys_permission` (`code`, `name`, `module`, `type`, `sort_order`) VALUES
  ('about:edit', '编辑关于我们', '关于我们', 'BUTTON', 105),
  ('about:view', '关于我们', '关于我们', 'MENU', 104),
  ('admin:create', '新增管理员', '系统', 'BUTTON', 81),
  ('admin:delete', '删除管理员', '系统', 'BUTTON', 83),
  ('admin:edit', '编辑管理员', '系统', 'BUTTON', 82),
  ('admin:list', '管理员列表', '系统', 'MENU', 80),
  ('ai:edit', '编辑 AI 配置', 'AI 配置', 'BUTTON', 112),
  ('ai:test', '测试 AI 连通性', 'AI 配置', 'BUTTON', 113),
  ('ai:tool:edit', '编辑 AI 工具', 'AI 工具', 'BUTTON', 122),
  ('ai:tool:use', '调用 AI 工具', 'AI 工具', 'BUTTON', 123),
  ('ai:tool:view', '查看 AI 工具', 'AI 工具', 'MENU', 121),
  ('ai:view', 'AI 配置', 'AI 配置', 'MENU', 111),
  ('capability:create', '新增培养能力', '我们培养的能力', 'BUTTON', 101),
  ('capability:delete', '删除培养能力', '我们培养的能力', 'BUTTON', 103),
  ('capability:edit', '编辑培养能力', '我们培养的能力', 'BUTTON', 102),
  ('capability:list', '培养能力列表', '我们培养的能力', 'MENU', 100),
  ('carousel:create', '新增轮播图', '轮播图', 'BUTTON', 41),
  ('carousel:delete', '删除轮播图', '轮播图', 'BUTTON', 43),
  ('carousel:edit', '编辑轮播图', '轮播图', 'BUTTON', 42),
  ('carousel:list', '轮播图列表', '轮播图', 'MENU', 40),
  ('comment:audit', '审核评论', '评论', 'BUTTON', 51),
  ('comment:delete', '删除评论', '评论', 'BUTTON', 53),
  ('comment:list', '评论列表', '评论', 'MENU', 50),
  ('comment:reply', '回复评论', '评论', 'BUTTON', 52),
  ('dashboard:view', '查看仪表盘', '仪表盘', 'MENU', 10),
  ('file:list', '文件列表', '系统', 'MENU', 91),
  ('file:upload', '文件上传', '系统', 'BUTTON', 92),
  ('footer:edit', '编辑页脚设置', '页脚设置', 'BUTTON', 107),
  ('footer:view', '页脚设置', '页脚设置', 'MENU', 106),
  ('join:edit', '编辑加入我们', '加入我们', 'BUTTON', 115),
  ('join:view', '加入我们', '加入我们', 'MENU', 114),
  ('learning:category', '资料分类管理', '学习资料', 'MENU', 99),
  ('learning:create', '新增学习资料', '学习资料', 'BUTTON', 95),
  ('learning:delete', '删除学习资料', '学习资料', 'BUTTON', 97),
  ('learning:edit', '编辑学习资料', '学习资料', 'BUTTON', 96),
  ('learning:list', '学习资料列表', '学习资料', 'MENU', 94),
  ('learning:publish', '上下架资料', '学习资料', 'BUTTON', 98),
  ('log:list', '审计日志', '系统', 'MENU', 90),
  ('message:delete', '删除留言', '留言', 'BUTTON', 62),
  ('message:list', '留言列表', '留言', 'MENU', 60),
  ('message:reply', '回复留言', '留言', 'BUTTON', 61),
  ('news:create', '新增新闻', '新闻', 'BUTTON', 31),
  ('news:delete', '删除新闻', '新闻', 'BUTTON', 33),
  ('news:edit', '编辑新闻', '新闻', 'BUTTON', 32),
  ('news:list', '新闻列表', '新闻', 'MENU', 30),
  ('news:publish', '发布新闻', '新闻', 'BUTTON', 34),
  ('retention:edit', '编辑保留策略', '数据保留', 'BUTTON', 109),
  ('retention:run', '立即执行清理', '数据保留', 'BUTTON', 110),
  ('retention:view', '数据保留', '数据保留', 'MENU', 108),
  ('role:create', '新增角色', '系统', 'BUTTON', 85),
  ('role:delete', '删除角色', '系统', 'BUTTON', 87),
  ('role:edit', '编辑角色', '系统', 'BUTTON', 86),
  ('role:list', '角色列表', '系统', 'MENU', 84),
  ('service:create', '新增招收方向', '主要招收方向', 'BUTTON', 45),
  ('service:delete', '删除招收方向', '主要招收方向', 'BUTTON', 47),
  ('service:edit', '编辑招收方向', '主要招收方向', 'BUTTON', 46),
  ('service:list', '招收方向列表', '主要招收方向', 'MENU', 44),
  ('system:backup', '数据备份', '系统', 'MENU', 89),
  ('system:settings', '系统设置', '系统', 'MENU', 88),
  ('system:sql', 'SQL 监控', '系统', 'MENU', 93),
  ('user:ban', '禁言/解禁', '用户', 'BUTTON', 72),
  ('user:bind-phone', '绑定旧账号手机号', '用户', 'BUTTON', 74),
  ('user:delete', '注销用户', '用户', 'BUTTON', 73),
  ('user:edit', '编辑用户资料', '用户', 'BUTTON', 75),
  ('user:list', '用户列表', '用户', 'MENU', 70),
  ('user:view', '用户详情', '用户', 'BUTTON', 71),
  ('work:create', '新增作品', '作品', 'BUTTON', 21),
  ('work:delete', '删除作品', '作品', 'BUTTON', 23),
  ('work:edit', '编辑作品', '作品', 'BUTTON', 22),
  ('work:list', '作品列表', '作品', 'MENU', 20),
  ('work:publish', '上下架作品', '作品', 'BUTTON', 24);

INSERT IGNORE INTO `sys_role` (`code`, `name`, `description`, `builtin`) VALUES
  ('AUDITOR', '审计员', '只读监督', 0),
  ('EDITOR', '内容小编', '仪表盘 + 作品/新闻/轮播/招收方向/培养能力管理', 1),
  ('OPERATOR', '客服运营', '仪表盘 + 评论/留言/用户管理', 1),
  ('SUPER_ADMIN', '超级管理员', '全部权限（代码级绕过，见实施方案 §5.1）', 1);

INSERT IGNORE INTO `site_settings` (`group_name`, `setting_key`, `setting_value`, `description`, `updated_at`) VALUES
  ('about', 'about.content', '独角兽工作室是一支充满激情的技术团队，专注于为客户提供高质量的数字产品与服务。我们相信，优秀的技术结合出色的设计，能够为用户带来非凡的体验。\n\n自创立以来，我们已为众多企业提供了 Web 开发、移动应用、大模型学术研究等方面的专业服务，赢得了客户的广泛认可与好评。', '关于我们 · 正文，段落间用空行分隔', CURRENT_TIMESTAMP),
  ('about', 'about.eyebrow', 'About Us', '关于我们 · 顶部小标签', CURRENT_TIMESTAMP),
  ('about', 'about.image_url', '', '关于我们 · 右侧配图（留空则使用站点默认 LOGO）', CURRENT_TIMESTAMP),
  ('about', 'about.stats', '[{"value":"50+","label":"完成项目"},{"value":"30+","label":"合作客户"},{"value":"5+","label":"年行业经验"}]', '关于我们 · 统计指标（JSON 数组）', CURRENT_TIMESTAMP),
  ('about', 'about.subtitle', '一群热爱技术与创造力的伙伴，致力于在数字世界中创造价值', '关于我们 · 副标题', CURRENT_TIMESTAMP),
  ('about', 'about.title', '关于独角兽工作室', '关于我们 · 主标题', CURRENT_TIMESTAMP),
  ('ai', 'ai.api_key', '', 'AI 助手 · API Key（AES-256-GCM 加密存储，后台填写，勿手工明文写入）', CURRENT_TIMESTAMP),
  ('ai', 'ai.api_url', 'https://open.bigmodel.cn/api/paas/v4', 'AI 助手 · API 地址（兼容 OpenAI 协议的 base URL，如 https://api.openai.com/v1）', CURRENT_TIMESTAMP),
  ('ai', 'ai.enabled', 'true', 'AI 助手 · 功能总开关（false 则前台对话不可用）', CURRENT_TIMESTAMP),
  ('ai', 'ai.max_tokens', '1024', 'AI 助手 · 单次回复最大 token 数', CURRENT_TIMESTAMP),
  ('ai', 'ai.model', 'GLM-4.5-Air', 'AI 助手 · 模型名称（需与所填 API 地址的供应商一致）', CURRENT_TIMESTAMP),
  ('ai', 'ai.system_prompt', '你是独角兽工作室的AI助手，名叫小独角兽。你友好、专业，擅长回答关于工作室、技术学习和编程的问题。请用中文回答。', 'AI 助手 · 系统提示词（人设与回答风格）', CURRENT_TIMESTAMP),
  ('ai', 'ai.temperature', '0.7', 'AI 助手 · 采样温度（0~2，越大越随机）', CURRENT_TIMESTAMP),
  ('content', 'content.presentation.about', '{"principlesEyebrow":"02 / 我们相信","principlesTitle":"好的想法，\\n值得亲手实现。","principles":[{"title":"先有问题，再找答案。","description":"从真实的需要出发。理解一个问题，也是在寻找值得投入的方向。"},{"title":"把想法放进实践。","description":"让代码、设计与研究彼此连接。在动手尝试中，看见想法的形状。"},{"title":"让经验继续生长。","description":"把探索中的思考留下来。一次分享，也可能成为另一个人的起点。"}],"invitationTitle":"下一次探索，\\n一起出发。","invitationDescription":"带着好奇而来，让想法在这里相遇。","invitationLabel":"加入我们","invitationUrl":"/join"}', '页面展示默认配置（已有值保留）', CURRENT_TIMESTAMP),
  ('content', 'content.presentation.footer', '{"inheritBrand":false,"inheritContact":false,"brandTagline":"IN GOOD COMPANY. IN THE MAKING.","noteEyebrow":"KEEP THE CURIOSITY.","noteTitle":"把下一次好奇，\\n变成下一件作品。","noteLinkLabel":"和我们一起创造","noteLinkUrl":"/join","wordmark":"UNICORN"}', '页面展示默认配置（已有值保留）', CURRENT_TIMESTAMP),
  ('content', 'content.presentation.home', '{"eyebrow":"UNICORN STUDIO / 独角兽工作室","title":"让好奇心，\\n长出作品。","description":"围绕 AI、软件开发与真实项目，一起学习、实验，把想法做成可以体验的成果。","primaryLabel":"探索作品","primaryUrl":"/works","secondaryLabel":"加入我们","secondaryUrl":"/join","mediaMode":"carousel","featuredLimit":3,"worksEyebrow":"SELECTED WORKS","worksTitle":"想法的下一步，\\n是把它做出来。","worksDescription":"项目、竞赛与研究。\\n每一份成果，都是一次探索的回响。","capabilitiesEyebrow":"THE WAY WE GROW","capabilitiesTitle":"把问题拆开，\\n把可能性\\n做出来。","capabilitiesDescription":"在真实项目中学习，也在学习中创造。\\n让每一次探索都有回应，\\n让一起成长有一些具体。","directionsEyebrow":"FIND YOUR DIRECTION","directionsTitle":"好奇，\\n可以有很多方向。","directionsDescription":"找到你想深入的领域，\\n和一群有想法的人，一起向前。","invitationEyebrow":"YOUR NEXT CHAPTER","invitationTitle":"下一件作品，\\n与你有关。","invitationDescription":"带上你的好奇心。\\n我们期待和你一起，创造一点新东西。","invitationLabel":"一起开始","invitationUrl":"/join"}', '页面展示默认配置（已有值保留）', CURRENT_TIMESTAMP),
  ('content', 'content.presentation.join', '{"platform":"QQ","groupNumber":"","joinUrl":"","phoneHelp":"","invitationText":"带上你的好奇，\\n我们从一个想法开始。"}', '页面展示默认配置（已有值保留）', CURRENT_TIMESTAMP),
  ('footer', 'footer.brand_intro', '用技术与创意，打造卓越的数字产品。\n我们专注于AI智能开发、Agent开发与大模型学术研究。', '页脚 · 工作室简介（\n 换行）', CURRENT_TIMESTAMP),
  ('footer', 'footer.brand_name', '独角兽工作室', '页脚 · 工作室名称', CURRENT_TIMESTAMP),
  ('footer', 'footer.copyright_left', '© {year} 独角兽工作室 · 用创意点亮未来', '页脚 · 版权栏左侧文字（{year} 自动替换为当前年份）', CURRENT_TIMESTAMP),
  ('footer', 'footer.copyright_right', '每个人都有一颗炽热的 ❤️ and You', '页脚 · 版权栏右侧文字', CURRENT_TIMESTAMP),
  ('footer', 'footer.email', '1351094759@qq.com', '页脚 · 邮箱地址', CURRENT_TIMESTAMP),
  ('footer', 'footer.email_label', '邮箱：', '页脚 · 邮箱前缀标签文字', CURRENT_TIMESTAMP),
  ('footer', 'footer.links', '[{"label":"首页","url":"/","newTab":false},{"label":"作品","url":"/works","newTab":false},{"label":"关于我们","url":"/about","newTab":false},{"label":"学习资料","url":"/learning","newTab":false}]', '页脚 · 快速链接（JSON 数组：label/url/newTab）', CURRENT_TIMESTAMP),
  ('footer', 'footer.links_title', '快速链接', '页脚 · 快速链接栏目标题', CURRENT_TIMESTAMP),
  ('footer', 'footer.show_particles', 'false', '页脚 · 是否显示左侧装饰粒子动效（true/false）', CURRENT_TIMESTAMP),
  ('footer', 'footer.social_title', '关注我们', '页脚 · 关注我们栏目标题', CURRENT_TIMESTAMP),
  ('footer', 'footer.socials', '[{"icon":"github","iconUrl":"","url":"https://github.com","title":"GitHub"},{"icon":"qq","iconUrl":"","url":"https://qq.com","title":"QQ"}]', '页脚 · 社交图标（JSON 数组：icon/iconUrl/url/title）', CURRENT_TIMESTAMP),
  ('join', 'join.eyebrow', '', '加入我们 · 顶部小标题（留空则不显示）', CURRENT_TIMESTAMP),
  ('join', 'join.paragraphs', '在这里我们分享前沿技术、实战项目与设计创意，共同成长、一起探索数字世界的更多可能。\n\n扫描下方二维码，即可加入社群与我们直接交流。', '加入我们 · 正文段落，段落间用空行分隔', CURRENT_TIMESTAMP),
  ('join', 'join.qr_caption', 'QQ扫码加入社群', '加入我们 · 二维码下方说明文字', CURRENT_TIMESTAMP),
  ('join', 'join.qr_image', '', '加入我们 · 社群二维码图片（留空则使用站点默认二维码）', CURRENT_TIMESTAMP),
  ('join', 'join.subtitle', '欢迎加入独角兽工作室交流社群', '加入我们 · 副标题', CURRENT_TIMESTAMP),
  ('join', 'join.title', '加入我们', '加入我们 · 主标题', CURRENT_TIMESTAMP),
  ('retention', 'retention.days', '7', '数据保留 · 审计日志/SQL监控保留天数（可选 7/15/30/90）', CURRENT_TIMESTAMP),
  ('retention', 'retention.enabled', 'true', '数据保留 · 是否启用定时自动清理（true/false）', CURRENT_TIMESTAMP),
  ('retention', 'retention.last_run_at', '', '数据保留 · 上次清理执行时间（系统写入，勿手改）', CURRENT_TIMESTAMP),
  ('retention', 'retention.last_run_deleted', '', '数据保留 · 上次清理删除的审计日志条数（系统写入）', CURRENT_TIMESTAMP),
  ('retention', 'retention.last_run_trigger', '', '数据保留 · 上次清理触发方式（scheduled/manual，系统写入）', CURRENT_TIMESTAMP),
  ('retention', 'retention.sql_monitor_last_reset', '', '数据保留 · 上次 SQL 监控统计重置时间（系统写入，勿手改）', CURRENT_TIMESTAMP);

INSERT IGNORE INTO `learning_category` (`name`, `description`, `sort_order`, `status`) VALUES
  ('其他', '未归类学习资料', 99, 1),
  ('工程开发', '前后端与工程实践教程', 2, 1),
  ('算法竞赛', 'ACM/ICPC、蓝桥杯等竞赛资料', 1, 1),
  ('设计', 'UI/视觉设计学习资料', 3, 1);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN (
  'dashboard:view','work:list','work:create','work:edit','work:delete','work:publish',
  'news:list','news:create','news:edit','news:delete','news:publish',
  'carousel:list','carousel:create','carousel:edit','carousel:delete',
  'service:list','service:create','service:edit','service:delete','file:list','file:upload'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'OPERATOR' AND p.code IN (
  'dashboard:view','comment:list','comment:audit','comment:reply','comment:delete',
  'message:list','message:reply','message:delete',
  'user:list','user:view','user:ban'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'AUDITOR' AND p.code IN (
  'dashboard:view','log:list','work:list','news:list','carousel:list',
  'comment:list','message:list','user:list'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN (
  'learning:list','learning:create','learning:edit','learning:delete','learning:publish','learning:category'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN (
  'capability:list','capability:create','capability:edit','capability:delete'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN ('about:view','about:edit'))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN ('footer:view','footer:edit'))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN ('ai:view','ai:edit'))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN ('join:view','join:edit'))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN (
  'service:list','service:create','service:edit','service:delete'
))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE (r.code = 'EDITOR' AND p.code IN ('ai:tool:view'))
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r CROSS JOIN sys_permission p
WHERE (r.code = 'EDITOR' AND p.code = 'ai:tool:view')
AND (JSON_CONTAINS(@us_existing_roles, JSON_QUOTE(r.code)) = 0 OR JSON_CONTAINS(@us_existing_permissions, JSON_QUOTE(p.code)) = 0);
COMMIT;
SET SESSION group_concat_max_len = CAST(@us_original_group_concat_max_len AS UNSIGNED);
SET SESSION sql_mode = @us_original_sql_mode;
SELECT 'DEPLOYMENT_SQL_OK' AS result;
