-- ============================================================
-- 能力培养条目表（首页"我们培养的能力"）· MySQL 增量脚本
-- 用法：mysql -u root -p < backend/sql/add_service_item.sql
-- 幂等：可重复执行（IF NOT EXISTS + ON DUPLICATE KEY UPDATE）
-- 后续升级预留：
--   status 支持 draft/published/offline 草稿流（管理后台维护，无需改表）
--   sort_order 支持后台排序
--   created_at/updated_at 内容审计
--   icon 后续可无缝切换为图标库标识或图片 URL
-- ============================================================

USE unicorn_studio;

CREATE TABLE IF NOT EXISTS service_item (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  title       VARCHAR(100) NOT NULL COMMENT '能力标题',
  description VARCHAR(500) NOT NULL COMMENT '能力描述',
  icon        VARCHAR(50)  DEFAULT NULL COMMENT '图标（emoji 或图标库标识）',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  status      VARCHAR(20)  NOT NULL DEFAULT 'published' COMMENT '状态：published 展示 / offline 下线（预留 draft）',
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_service_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首页能力培养条目';

-- 种子数据（幂等，不覆盖运行期修改）
INSERT INTO service_item (id, title, description, icon, sort_order, status) VALUES
(1, 'AI智能应用开发', '使用前沿技术栈，构建高性能、响应式的智能化应用。', '🌐', 1, 'published'),
(2, '大模型学术论文研究', '聚焦大语言模型前沿方向，开展学术论文撰写与发表。', '🔬', 2, 'published'),
(3, 'Agent开发', '基于大模型开发智能体，支持自主规划、工具调用与任务落地。', '🤖', 3, 'published')
ON DUPLICATE KEY UPDATE id = id;

SELECT 'service_item 表创建/更新完成' AS status;
