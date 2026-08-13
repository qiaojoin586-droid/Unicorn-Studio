-- ============================================================
-- V1 基线：内容表（work/news/carousel/learning_resources/service_item）
-- 兼容 H2(MySQL 模式) 与 MySQL 8：
--   - 省略 ENGINE/CHARSET 子句（MySQL 8 默认即 InnoDB + utf8mb4）
--   - 省略 ON UPDATE CURRENT_TIMESTAMP（H2 不支持），updated_at 由 JPA @PreUpdate 维护
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
CREATE TABLE learning_resources (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  title       VARCHAR(200) NOT NULL,
  description VARCHAR(500) NOT NULL,
  url         VARCHAR(255),
  category    VARCHAR(50),
  cover_url   VARCHAR(255),
  sort_order  INT NOT NULL DEFAULT 0,
  status      INTEGER NOT NULL DEFAULT 1,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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
