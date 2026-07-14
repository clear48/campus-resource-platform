-- 下载增量同步幂等化迁移：可在已有 campus_resource_platform 数据库重复执行。
-- 唯一键 (batch_id, resource_id) 是 MySQL 已提交但 Redis HDEL 失败时防止重复累计的最终兜底。
CREATE TABLE IF NOT EXISTS `download_delta_sync_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '幂等明细ID',
  `batch_id` CHAR(36) NOT NULL COMMENT 'Redis隔离下载增量批次UUID，兼容legacy-active',
  `resource_id` BIGINT NOT NULL COMMENT '资料ID，逻辑关联resource.id',
  `delta` BIGINT NOT NULL COMMENT '该批次对资料的下载增量',
  `confirmed_at` DATETIME DEFAULT NULL COMMENT 'Redis HDEL确认成功时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_download_delta_sync_batch_resource` (`batch_id`, `resource_id`),
  KEY `idx_download_delta_sync_confirmed_created` (`confirmed_at`, `created_at`),
  CONSTRAINT `chk_download_delta_sync_item_delta` CHECK (`delta` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Redis下载增量同步幂等明细表';
