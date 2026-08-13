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
