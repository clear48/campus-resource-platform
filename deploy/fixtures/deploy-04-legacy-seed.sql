USE `campus_resource_platform`;

-- 固定 ID 让结构与数据断言可重复，所有记录仅存在于本次隔离数据卷。
INSERT INTO `user` (`id`, `username`, `password_hash`, `nickname`, `role`, `status`)
VALUES
  (900001, 'deploy04_uploader', '$2a$10$synthetic', 'DEPLOY-04 Uploader', 1, 1),
  (900002, 'deploy04_reader', '$2a$10$synthetic', 'DEPLOY-04 Reader', 1, 1);

INSERT INTO `category` (`id`, `parent_id`, `category_name`, `description`, `sort_order`, `status`)
VALUES (900001, 0, 'DEPLOY-04 Synthetic', 'isolated migration fixture', 900001, 1);

INSERT INTO `file_info` (`id`, `file_md5`, `original_name`, `stored_name`, `file_ext`, `mime_type`,
                         `file_size`, `storage_type`, `storage_path`, `uploader_id`, `ref_count`, `status`)
VALUES (900001, '04c104c104c104c104c104c104c104c1', 'legacy.txt', 'deploy04-legacy.txt', 'txt',
        'text/plain', 17, 1, 'deploy04/legacy.txt', 900001, 5, 1);

INSERT INTO `resource` (`id`, `title`, `description`, `category_id`, `course_name`, `resource_type`, `tags`,
                        `file_id`, `uploader_id`, `status`, `reject_reason`, `offline_reason`, `view_count`,
                        `download_count`, `favorite_count`, `hot_score`, `approved_at`, `offline_at`)
VALUES
  (900001, 'Approved', 'approved legacy row', 900001, 'DEPLOY-04', 2, 'legacy', 900001, 900001, 1,
   NULL, NULL, 11, 7, 2, 8.50, '2026-01-01 00:00:00', NULL),
  (900002, 'Cross-user rejected', 'historical cross-user reference', 900001, 'DEPLOY-04', 2, 'legacy',
   900001, 900002, 2, 'synthetic reject', NULL, 3, 5, 0, 1.00, NULL, NULL),
  (900003, 'Same-user offline', 'inactive duplicate', 900001, 'DEPLOY-04', 2, 'legacy', 900001, 900001, 3,
   NULL, 'synthetic offline', 2, 4, 0, 0.50, NULL, '2026-01-02 00:00:00'),
  (900004, 'Pending', 'guard status zero', 900001, 'DEPLOY-04', 2, 'legacy', 900001, 900002, 0,
   NULL, NULL, 1, 3, 0, 0.25, NULL, NULL),
  (900005, 'Deleted', 'guard status four', 900001, 'DEPLOY-04', 2, 'legacy', 900001, 900002, 4,
   NULL, NULL, 0, 2, 0, 0.00, NULL, NULL);

-- legacy 数据在迁移前不得有相同上传者、文件的有效状态重复。
SET @deploy04_active_duplicates := (
  SELECT COUNT(*) FROM (
    SELECT uploader_id, file_id FROM resource WHERE status IN (0, 1)
    GROUP BY uploader_id, file_id HAVING COUNT(*) > 1
  ) AS duplicate_groups
);
SET @deploy04_seed_ok := IF(@deploy04_active_duplicates = 0, 1, 0);
SELECT IF(@deploy04_seed_ok = 1, 'DEPLOY04_SEED_OK', 'DEPLOY04_SEED_INVALID') AS deploy04_seed_status;
