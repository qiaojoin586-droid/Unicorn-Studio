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
