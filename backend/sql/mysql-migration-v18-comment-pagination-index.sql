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
