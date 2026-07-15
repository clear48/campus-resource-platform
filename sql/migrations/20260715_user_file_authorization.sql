-- IMP-005：将物理文件去重与用户可引用权限解耦。
-- 执行前请备份数据库；该迁移不删除或改写现有 resource/file_info 数据。

CREATE TABLE IF NOT EXISTS `user_file_authorization` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '授权关系ID',
  `user_id` BIGINT NOT NULL COMMENT '获得文件引用权限的用户ID，逻辑关联user.id',
  `file_id` BIGINT NOT NULL COMMENT '已验证上传内容对应的文件ID，逻辑关联file_info.id',
  `source_type` TINYINT NOT NULL COMMENT '授权来源: 1首次上传 2实际上传命中去重 3历史迁移',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_file_authorization` (`user_id`, `file_id`),
  KEY `idx_file_authorized_user` (`file_id`, `user_id`),
  CONSTRAINT `chk_file_authorization_source` CHECK (`source_type` IN (1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户文件引用授权表';

-- 只回填可由 file_info 直接证明的首次上传者，不把历史跨用户引用自动提升为可信授权。
INSERT IGNORE INTO `user_file_authorization` (`user_id`, `file_id`, `source_type`, `created_at`)
SELECT `uploader_id`, `id`, 3, `created_at`
FROM `file_info`;

-- 上线前人工核查本查询结果；这些历史资料不受读取影响，但其上传者不会自动取得再次引用该文件的权限。
SELECT r.id AS resource_id,
       r.uploader_id AS resource_uploader_id,
       r.file_id,
       f.uploader_id AS first_file_uploader_id,
       r.status
FROM `resource` r
JOIN `file_info` f ON f.id = r.file_id
WHERE r.uploader_id <> f.uploader_id
ORDER BY r.id;
