-- 在线单文件保护：先建立可恢复副本记录和异常恢复审计，再启用应用功能开关。
-- 执行前要求已存在file_info表；本迁移不移动、修改或删除任何现有上传文件。

CREATE TABLE IF NOT EXISTS `file_backup_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '单文件副本记录ID',
  `file_id` BIGINT NOT NULL COMMENT '逻辑关联file_info.id',
  `backup_path` VARCHAR(500) DEFAULT NULL COMMENT '副本绝对路径，仅供内部恢复使用',
  `file_size` BIGINT NOT NULL COMMENT '副本文件大小，单位字节',
  `file_md5` CHAR(32) NOT NULL COMMENT '与file_info一致的MD5',
  `file_sha256` CHAR(64) DEFAULT NULL COMMENT '副本SHA-256，用于恢复前后完整性校验',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0待处理 1可恢复 2失败 3已退役',
  `last_error_code` VARCHAR(64) DEFAULT NULL COMMENT '最近一次失败错误码，不保存敏感绝对路径',
  `backup_completed_at` DATETIME DEFAULT NULL COMMENT '副本校验并发布完成时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_backup_file` (`file_id`),
  KEY `idx_file_backup_status_file` (`status`, `file_id`),
  CONSTRAINT `chk_file_backup_status` CHECK (`status` IN (0, 1, 2, 3)),
  CONSTRAINT `chk_file_backup_size` CHECK (`file_size` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='在线单文件只读副本记录';

CREATE TABLE IF NOT EXISTS `file_integrity_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件完整性事件ID',
  `file_id` BIGINT NOT NULL COMMENT '逻辑关联file_info.id',
  `backup_record_id` BIGINT DEFAULT NULL COMMENT '本次恢复使用的副本记录ID',
  `event_type` TINYINT NOT NULL COMMENT '类型: 1缺失 2大小不一致 3摘要不一致 4存储根不可用',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态: 0已发现 1已确认 2恢复中 3已恢复 4失败 5忽略',
  `detected_count` INT NOT NULL DEFAULT 1 COMMENT '连续检测次数，至少两次才允许恢复',
  `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '自动恢复尝试次数',
  `error_code` VARCHAR(64) DEFAULT NULL COMMENT '稳定错误码，不保存堆栈或敏感路径',
  `detected_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次发现时间',
  `confirmed_at` DATETIME DEFAULT NULL COMMENT '二次确认时间',
  `recovered_at` DATETIME DEFAULT NULL COMMENT '恢复完成时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_integrity_event_file_status` (`file_id`, `status`, `id`),
  KEY `idx_integrity_event_status_created` (`status`, `created_at`),
  CONSTRAINT `chk_integrity_event_type` CHECK (`event_type` IN (1, 2, 3, 4)),
  CONSTRAINT `chk_integrity_event_status` CHECK (`status` IN (0, 1, 2, 3, 4, 5)),
  CONSTRAINT `chk_integrity_event_count` CHECK (`detected_count` >= 1 AND `attempt_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件异常检测与自动恢复审计';
