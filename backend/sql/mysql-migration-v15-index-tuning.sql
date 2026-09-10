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
