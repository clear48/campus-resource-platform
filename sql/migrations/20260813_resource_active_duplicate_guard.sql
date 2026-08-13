-- 资料有效状态条件唯一约束迁移，可在 MySQL 8.x 的既有库上重复执行。
-- 业务规则：同一用户、同一物理文件只能存在一条待审核或已通过资料；拒绝、下架、删除后允许重新提交。
-- 执行前请备份数据库。迁移只增加生成列和唯一索引，不删除或改写既有资料。

DELIMITER $$

DROP PROCEDURE IF EXISTS `migrate_resource_active_duplicate_guard`$$
CREATE PROCEDURE `migrate_resource_active_duplicate_guard`()
BEGIN
    DECLARE duplicate_group_count BIGINT DEFAULT 0;
    DECLARE guard_column_count INT DEFAULT 0;
    DECLARE guard_index_count INT DEFAULT 0;

    -- DDL 前主动中止存量脏数据，避免由唯一索引错误代替可读的迁移诊断。
    SELECT COUNT(*)
      INTO duplicate_group_count
      FROM (
          SELECT `uploader_id`, `file_id`
            FROM `resource`
           WHERE `status` IN (0, 1)
           GROUP BY `uploader_id`, `file_id`
          HAVING COUNT(*) > 1
      ) AS duplicate_groups;

    IF duplicate_group_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'resource 存在待审核/已通过重复资料，请先核查并清理后再执行迁移';
    END IF;

    SELECT COUNT(*)
      INTO guard_column_count
      FROM `information_schema`.`COLUMNS`
     WHERE `TABLE_SCHEMA` = DATABASE()
       AND `TABLE_NAME` = 'resource'
       AND `COLUMN_NAME` = 'active_duplicate_guard';

    IF guard_column_count = 0 THEN
        ALTER TABLE `resource`
            ADD COLUMN `active_duplicate_guard` TINYINT
                GENERATED ALWAYS AS (CASE WHEN `status` IN (0, 1) THEN 1 ELSE NULL END) STORED
                COMMENT '有效资料防重标记: 待审核/已通过为1，其余状态为NULL'
                AFTER `status`;
    END IF;

    SELECT COUNT(*)
      INTO guard_index_count
      FROM `information_schema`.`STATISTICS`
     WHERE `TABLE_SCHEMA` = DATABASE()
       AND `TABLE_NAME` = 'resource'
       AND `INDEX_NAME` = 'uk_resource_active_duplicate';

    IF guard_index_count = 0 THEN
        ALTER TABLE `resource`
            ADD UNIQUE KEY `uk_resource_active_duplicate`
                (`uploader_id`, `file_id`, `active_duplicate_guard`);
    END IF;
END$$

CALL `migrate_resource_active_duplicate_guard`()$$
DROP PROCEDURE `migrate_resource_active_duplicate_guard`$$

DELIMITER ;

-- 迁移后核查：应返回 0；若非 0，说明约束结构被人工修改或迁移未完整执行。
SELECT COUNT(*) AS active_duplicate_group_count
FROM (
    SELECT `uploader_id`, `file_id`
    FROM `resource`
    WHERE `status` IN (0, 1)
    GROUP BY `uploader_id`, `file_id`
    HAVING COUNT(*) > 1
) AS duplicate_groups;
