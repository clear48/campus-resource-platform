DROP TABLE IF EXISTS audit_record;
DROP TABLE IF EXISTS download_delta_sync_item;
DROP TABLE IF EXISTS `resource`;
DROP TABLE IF EXISTS user_file_authorization;
DROP TABLE IF EXISTS file_info;
DROP TABLE IF EXISTS category;

CREATE TABLE category (
  id BIGINT NOT NULL AUTO_INCREMENT,
  parent_id BIGINT NOT NULL DEFAULT 0,
  category_name VARCHAR(100) NOT NULL,
  description VARCHAR(255),
  sort_order INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE TABLE file_info (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_md5 CHAR(32) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  file_ext VARCHAR(20) NOT NULL,
  mime_type VARCHAR(100),
  file_size BIGINT NOT NULL DEFAULT 0,
  storage_type TINYINT NOT NULL DEFAULT 1,
  storage_path VARCHAR(500) NOT NULL,
  uploader_id BIGINT NOT NULL,
  ref_count INT NOT NULL DEFAULT 1,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_md5_size (file_md5, file_size)
);

CREATE TABLE user_file_authorization (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  file_id BIGINT NOT NULL,
  source_type TINYINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_file_authorization (user_id, file_id),
  KEY idx_file_authorized_user (file_id, user_id)
);

CREATE TABLE `resource` (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(150) NOT NULL,
  description TEXT,
  category_id BIGINT NOT NULL,
  course_name VARCHAR(100) NOT NULL,
  resource_type TINYINT NOT NULL DEFAULT 99,
  tags VARCHAR(255),
  file_id BIGINT NOT NULL,
  uploader_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  reject_reason VARCHAR(500),
  offline_reason VARCHAR(500),
  view_count BIGINT NOT NULL DEFAULT 0,
  download_count BIGINT NOT NULL DEFAULT 0,
  favorite_count BIGINT NOT NULL DEFAULT 0,
  hot_score DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
  approved_at DATETIME,
  offline_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_resource_status_created (status, created_at),
  KEY idx_resource_uploader_status (uploader_id, status, created_at),
  KEY idx_resource_file (file_id)
);

CREATE TABLE download_delta_sync_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id CHAR(36) NOT NULL,
  resource_id BIGINT NOT NULL,
  delta BIGINT NOT NULL,
  confirmed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_download_delta_sync_batch_resource (batch_id, resource_id),
  KEY idx_download_delta_sync_confirmed_created (confirmed_at, created_at)
);

CREATE TABLE audit_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  resource_id BIGINT NOT NULL,
  auditor_id BIGINT NOT NULL,
  action_type TINYINT NOT NULL,
  before_status TINYINT NOT NULL,
  after_status TINYINT NOT NULL,
  audit_reason VARCHAR(500),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_audit_resource_created (resource_id, created_at),
  KEY idx_audit_auditor_created (auditor_id, created_at),
  KEY idx_audit_action_created (action_type, created_at)
);
