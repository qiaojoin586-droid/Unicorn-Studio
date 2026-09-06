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
