-- ==========================================================================
-- Unicorn Studio · 迁移后校验（03-verify.sql）
-- --------------------------------------------------------------------------
-- 用法：
--   mysql --default-character-set=utf8mb4 -h<host> -u<user> -p <DB名> < 03-verify.sql
-- 期望：每项都返回 OK；出现 FAIL 的项按注释定位原因。
-- ==========================================================================
SET NAMES utf8mb4;

SELECT '== 1. 库默认字符集/排序规则（应为 utf8mb4 / utf8mb4_0900_ai_ci）==' AS `check`;
SELECT DEFAULT_CHARACTER_SET_NAME AS charset, DEFAULT_COLLATION_NAME AS collation,
       IF(DEFAULT_COLLATION_NAME = 'utf8mb4_0900_ai_ci', 'OK', 'FAIL') AS result
FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE();

SELECT '== 2. 表数量（应为 23）==' AS `check`;
SELECT COUNT(*) AS table_count, IF(COUNT(*) = 23, 'OK', 'FAIL') AS result
FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';

SELECT '== 3. 列排序规则是否统一（不该有 utf8mb4_unicode_ci 混入）==' AS `check`;
SELECT COLLATION_NAME AS collation, COUNT(*) AS cnt,
       IF(COLLATION_NAME = 'utf8mb4_0900_ai_ci', 'OK', 'FAIL') AS result
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND COLLATION_NAME IS NOT NULL
GROUP BY COLLATION_NAME;

SELECT '== 4. 外键数量（应为 4：RBAC 关联表）==' AS `check`;
SELECT COUNT(*) AS fk_count, IF(COUNT(*) = 4, 'OK', 'FAIL') AS result
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY';

SELECT '== 5. 逐表行数 ==' AS `check`;
SELECT 'capability_item' AS tbl, COUNT(*) AS rows_ FROM capability_item
UNION ALL SELECT 'carousel', COUNT(*) FROM carousel
UNION ALL SELECT 'sys_permission', COUNT(*) FROM sys_permission
UNION ALL SELECT 'sys_role', COUNT(*) FROM sys_role
UNION ALL SELECT 'sys_role_permission', COUNT(*) FROM sys_role_permission
UNION ALL SELECT 'sys_user', COUNT(*) FROM sys_user
UNION ALL SELECT 'sys_user_role', COUNT(*) FROM sys_user_role
UNION ALL SELECT 'site_settings', COUNT(*) FROM site_settings
UNION ALL SELECT 'work', COUNT(*) FROM work
UNION ALL SELECT 'news', COUNT(*) FROM news
UNION ALL SELECT 'service_item', COUNT(*) FROM service_item
UNION ALL SELECT 'learning_resources', COUNT(*) FROM learning_resources
UNION ALL SELECT 'learning_category', COUNT(*) FROM learning_category
UNION ALL SELECT 'upload_file', COUNT(*) FROM upload_file
UNION ALL SELECT 'visit_log', COUNT(*) FROM visit_log
ORDER BY tbl;

SELECT '== 6. 权限表中文是否乱码（应全为可读中文）==' AS `check`;
SELECT id, code, name, module,
       IF(name REGEXP '[\\x{4e00}-\\x{9fa5}]' OR name REGEXP '^[A-Za-z]', 'OK', 'FAIL') AS result
FROM sys_permission WHERE id BETWEEN 41 AND 50 ORDER BY id;

SELECT '== 7. site_settings 分组（应含 about/ai/footer/join/retention）==' AS `check`;
SELECT group_name, COUNT(*) AS cnt FROM site_settings GROUP BY group_name ORDER BY group_name;

SELECT '== 8. 关键配置组是否齐全 ==' AS `check`;
SELECT g AS group_name,
       IF(EXISTS(SELECT 1 FROM site_settings s WHERE s.group_name = g), 'OK', 'FAIL') AS result
FROM (SELECT 'about' g UNION SELECT 'footer' UNION SELECT 'join' UNION ALL
      SELECT 'ai' UNION SELECT 'retention' UNION SELECT 'basic') t;

SELECT '== 9. AI Key 是否已配置（空 = 需登录后台填写；非空但解密失败 = APP_CRYPTO_SECRET 不一致）==' AS `check`;
SELECT setting_key, LENGTH(setting_value) AS len,
       IF(setting_value = '', 'EMPTY(后台填写)', 'SET') AS result
FROM site_settings WHERE setting_key = 'ai.api_key';

SELECT '== 10. upload_file 记录数与磁盘文件数（服务器上应一致，否则前台图片 404）==' AS `check`;
SELECT COUNT(*) AS db_rows, COUNT(DISTINCT url) AS distinct_urls FROM upload_file;

SELECT '== 11. 关键内容表非空确认（迁移成功的直接证据）==' AS `check`;
SELECT
  (SELECT COUNT(*) FROM work WHERE status = 'published')    AS published_works,
  (SELECT COUNT(*) FROM news WHERE status = 'published')    AS published_news,
  (SELECT COUNT(*) FROM carousel WHERE page_key = 'home')   AS home_carousel,
  (SELECT COUNT(*) FROM site_settings WHERE group_name='join') AS join_settings;

SELECT '== 12. AI 记忆模块两张表及其索引对象数（ai_conversation 应 3 = 主键1+索引2；ai_message 应 6 = 主键1+唯一1+索引4）==' AS `check`;
SELECT TABLE_NAME AS tbl,
       COUNT(DISTINCT INDEX_NAME) AS idx_objects,
       COUNT(*)                   AS index_rows,
       IF(COUNT(DISTINCT INDEX_NAME) = IF(TABLE_NAME = 'ai_message', 6, 3), 'OK', 'FAIL') AS result
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('ai_conversation', 'ai_message')
GROUP BY TABLE_NAME
ORDER BY tbl;

-- ==========================================================================
-- 附加提醒（脚本无法自动校验，需人工确认）：
--   1) Redis 缓存：若服务器已启动过后端并产生缓存，导入数据后必须清缓存，
--      否则前台可能读到旧内容（缓存键 cache:site:settings / cache:*:page）。
--   2) 文件目录：data/uploads/ 需单独 rsync/scp 到服务器项目根的 data/uploads/。
--   3) 环境变量：APP_CRYPTO_SECRET 必须与源环境一致，否则 ai.api_key 无法解密。
-- ==========================================================================
