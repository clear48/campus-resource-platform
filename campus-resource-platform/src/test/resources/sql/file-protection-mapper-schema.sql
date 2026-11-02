DROP TABLE IF EXISTS file_integrity_event;
DROP TABLE IF EXISTS file_backup_record;
DROP TABLE IF EXISTS file_info;

CREATE TABLE file_info (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_md5 CHAR(32) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  stored_name VARCHAR(255) NOT NULL,
  file_ext VARCHAR(20) NOT NULL,
  mime_type VARCHAR(100),
  file_size BIGINT NOT NULL,
  storage_type TINYINT NOT NULL,
  storage_path VARCHAR(500) NOT NULL,
  uploader_id BIGINT NOT NULL,
  ref_count INT NOT NULL,
  status TINYINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_md5_size (file_md5, file_size)
);

CREATE TABLE file_backup_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  backup_path VARCHAR(500),
  file_size BIGINT NOT NULL,
  file_md5 CHAR(32) NOT NULL,
  file_sha256 CHAR(64),
  status TINYINT NOT NULL DEFAULT 0,
  last_error_code VARCHAR(64),
  backup_completed_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_backup_file (file_id)
);

CREATE TABLE file_integrity_event (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_id BIGINT NOT NULL,
  backup_record_id BIGINT,
  event_type TINYINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  detected_count INT NOT NULL DEFAULT 1,
  attempt_count INT NOT NULL DEFAULT 0,
  error_code VARCHAR(64),
  detected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  confirmed_at DATETIME,
  recovered_at DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
