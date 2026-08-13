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
