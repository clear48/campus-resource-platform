CREATE DATABASE IF NOT EXISTS `campus_resource_platform`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `campus_resource_platform`;

CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL COMMENT '登录账号，可使用学号或工号',
  `password_hash` VARCHAR(255) NOT NULL COMMENT '加密后的密码',
  `nickname` VARCHAR(50) NOT NULL COMMENT '昵称或姓名',
  `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
  `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
  `role` TINYINT NOT NULL DEFAULT 1 COMMENT '角色: 1学生 2管理员',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态: 0禁用 1正常',
  `avatar_url` VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最近登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`),
  UNIQUE KEY `uk_user_phone` (`phone`),
  KEY `idx_user_role_status` (`role`, `status`),
  CONSTRAINT `chk_user_role` CHECK (`role` IN (1, 2)),
  CONSTRAINT `chk_user_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分类ID',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父分类ID，0表示一级分类',
  `category_name` VARCHAR(100) NOT NULL COMMENT '分类名称',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '分类说明',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '分类状态: 0禁用 1启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_category_parent_name` (`parent_id`, `category_name`),
  KEY `idx_category_parent_status` (`parent_id`, `status`, `sort_order`),
  CONSTRAINT `chk_category_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分类表';

CREATE TABLE IF NOT EXISTS `file_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文件ID',
  `file_md5` CHAR(32) NOT NULL COMMENT '文件MD5，用于文件去重',
  `original_name` VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
  `stored_name` VARCHAR(255) NOT NULL COMMENT '存储系统中的文件名',
  `file_ext` VARCHAR(20) NOT NULL COMMENT '文件扩展名',
  `mime_type` VARCHAR(100) DEFAULT NULL COMMENT '文件MIME类型',
  `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
  `storage_type` TINYINT NOT NULL DEFAULT 1 COMMENT '存储类型: 1本地 2MinIO 3OSS',
  `storage_path` VARCHAR(500) NOT NULL COMMENT '文件存储路径或对象存储Key',
  `uploader_id` BIGINT NOT NULL COMMENT '首次上传该文件的用户ID，逻辑关联user.id',
  `ref_count` INT NOT NULL DEFAULT 1 COMMENT '文件引用次数',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '文件状态: 1正常 2已删除',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_md5_size` (`file_md5`, `file_size`),
  KEY `idx_file_uploader_created` (`uploader_id`, `created_at`),
  CONSTRAINT `chk_file_storage_type` CHECK (`storage_type` IN (1, 2, 3)),
  CONSTRAINT `chk_file_status` CHECK (`status` IN (1, 2)),
  CONSTRAINT `chk_file_size` CHECK (`file_size` >= 0),
  CONSTRAINT `chk_file_ref_count` CHECK (`ref_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件信息表';

CREATE TABLE IF NOT EXISTS `resource` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '资料ID',
  `title` VARCHAR(150) NOT NULL COMMENT '资料标题',
  `description` TEXT COMMENT '资料简介',
  `category_id` BIGINT NOT NULL COMMENT '分类ID，逻辑关联category.id',
  `course_name` VARCHAR(100) NOT NULL COMMENT '课程名称',
  `resource_type` TINYINT NOT NULL DEFAULT 99 COMMENT '资料类型: 1课件 2笔记 3真题 4实验报告 5课程设计 99其他',
  `tags` VARCHAR(255) DEFAULT NULL COMMENT '标签，初期可用逗号分隔',
  `file_id` BIGINT NOT NULL COMMENT '文件ID，逻辑关联file_info.id',
  `uploader_id` BIGINT NOT NULL COMMENT '上传用户ID，逻辑关联user.id',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '审核状态: 0待审核 1已通过 2已拒绝 3已下架 4已删除',
  `reject_reason` VARCHAR(500) DEFAULT NULL COMMENT '最近一次拒绝原因',
  `offline_reason` VARCHAR(500) DEFAULT NULL COMMENT '最近一次下架原因',
  `view_count` BIGINT NOT NULL DEFAULT 0 COMMENT '浏览次数',
  `download_count` BIGINT NOT NULL DEFAULT 0 COMMENT '下载次数，由Redis定时同步',
  `favorite_count` BIGINT NOT NULL DEFAULT 0 COMMENT '收藏次数',
  `hot_score` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '热度分快照，可由Redis排行榜定时回写',
  `approved_at` DATETIME DEFAULT NULL COMMENT '审核通过时间',
  `offline_at` DATETIME DEFAULT NULL COMMENT '下架时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_resource_status_created` (`status`, `created_at`),
  KEY `idx_resource_category_status` (`category_id`, `status`, `created_at`),
  KEY `idx_resource_uploader_status` (`uploader_id`, `status`, `created_at`),
  KEY `idx_resource_course_status` (`course_name`, `status`),
  KEY `idx_resource_file` (`file_id`),
  KEY `idx_resource_hot` (`status`, `hot_score`, `download_count`),
  CONSTRAINT `chk_resource_type` CHECK (`resource_type` IN (1, 2, 3, 4, 5, 99)),
  CONSTRAINT `chk_resource_status` CHECK (`status` IN (0, 1, 2, 3, 4)),
  CONSTRAINT `chk_resource_view_count` CHECK (`view_count` >= 0),
  CONSTRAINT `chk_resource_download_count` CHECK (`download_count` >= 0),
  CONSTRAINT `chk_resource_favorite_count` CHECK (`favorite_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资料表';

CREATE TABLE IF NOT EXISTS `favorite` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '收藏记录ID',
  `user_id` BIGINT NOT NULL COMMENT '收藏用户ID，逻辑关联user.id',
  `resource_id` BIGINT NOT NULL COMMENT '被收藏资料ID，逻辑关联resource.id',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '收藏状态: 0已取消 1已收藏',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次收藏时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_favorite_user_resource` (`user_id`, `resource_id`),
  KEY `idx_favorite_user_status_created` (`user_id`, `status`, `created_at`),
  KEY `idx_favorite_resource_status` (`resource_id`, `status`),
  CONSTRAINT `chk_favorite_status` CHECK (`status` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收藏表';

CREATE TABLE IF NOT EXISTS `download_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '下载记录ID',
  `user_id` BIGINT NOT NULL COMMENT '下载用户ID，逻辑关联user.id',
  `resource_id` BIGINT NOT NULL COMMENT '被下载资料ID，逻辑关联resource.id',
  `file_id` BIGINT NOT NULL COMMENT '被下载文件ID，逻辑关联file_info.id',
  `user_ip` VARCHAR(45) DEFAULT NULL COMMENT '用户IP，兼容IPv4和IPv6',
  `user_agent` VARCHAR(255) DEFAULT NULL COMMENT '浏览器或客户端信息',
  `download_status` TINYINT NOT NULL DEFAULT 1 COMMENT '下载状态: 1成功 2失败',
  `fail_reason` VARCHAR(255) DEFAULT NULL COMMENT '下载失败原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下载时间',
  PRIMARY KEY (`id`),
  KEY `idx_download_user_created` (`user_id`, `created_at`),
  KEY `idx_download_resource_created` (`resource_id`, `created_at`),
  KEY `idx_download_user_resource_created` (`user_id`, `resource_id`, `created_at`),
  CONSTRAINT `chk_download_status` CHECK (`download_status` IN (1, 2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='下载记录表';

CREATE TABLE IF NOT EXISTS `audit_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审核记录ID',
  `resource_id` BIGINT NOT NULL COMMENT '被审核资料ID，逻辑关联resource.id',
  `auditor_id` BIGINT NOT NULL COMMENT '审核管理员ID，逻辑关联user.id',
  `action_type` TINYINT NOT NULL COMMENT '操作类型: 1通过 2拒绝 3下架',
  `before_status` TINYINT NOT NULL COMMENT '操作前资料状态: 0待审核 1已通过 2已拒绝 3已下架 4已删除',
  `after_status` TINYINT NOT NULL COMMENT '操作后资料状态: 0待审核 1已通过 2已拒绝 3已下架 4已删除',
  `audit_reason` VARCHAR(500) DEFAULT NULL COMMENT '审核意见、拒绝原因或下架原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_resource_created` (`resource_id`, `created_at`),
  KEY `idx_audit_auditor_created` (`auditor_id`, `created_at`),
  KEY `idx_audit_action_created` (`action_type`, `created_at`),
  CONSTRAINT `chk_audit_action_type` CHECK (`action_type` IN (1, 2, 3)),
  CONSTRAINT `chk_audit_before_status` CHECK (`before_status` IN (0, 1, 2, 3, 4)),
  CONSTRAINT `chk_audit_after_status` CHECK (`after_status` IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核记录表';
