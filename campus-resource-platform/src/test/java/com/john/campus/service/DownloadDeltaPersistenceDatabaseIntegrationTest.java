package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.config.MyBatisConfig;
import com.john.campus.entity.Resource;
import com.john.campus.mapper.DownloadDeltaSyncItemMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.DownloadDeltaPersistenceServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/** 使用 H2 MySQL 模式验证幂等记录插入与 resource 原子累加处于同一真实 MyBatis 事务边界。 */
@MybatisTest
@Import({MyBatisConfig.class, DownloadDeltaPersistenceServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:download_delta_idempotency_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Sql(scripts = "/sql/resource-db-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DownloadDeltaPersistenceDatabaseIntegrationTest {

    private static final String BATCH_ID = "5f0e4d2e-7d33-4ab3-ae06-9c2afcc9d203";

    @Autowired
    private DownloadDeltaPersistenceService persistenceService;
    @Autowired
    private ResourceMapper resourceMapper;
    @Autowired
    private DownloadDeltaSyncItemMapper downloadDeltaSyncItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplySameIsolatedBatchOnlyOnceAndMarkRedisConfirmation() {
        insertApprovedResource(101L);

        persistenceService.persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));
        // 模拟 MySQL 已提交后 Redis HDEL 抛错：下一轮携带同一 batchId 时只能命中幂等记录。
        persistenceService.persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));
        persistenceService.markDownloadDeltasConfirmed(BATCH_ID, java.util.List.of(101L));

        assertThat(resourceMapper.selectById(101L).getDownloadCount()).isEqualTo(5L);
        assertThat(downloadDeltaSyncItemMapper.selectDelta(BATCH_ID, 101L)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed_at FROM download_delta_sync_item WHERE batch_id = ? AND resource_id = ?",
                LocalDateTime.class,
                BATCH_ID,
                101L)).isNotNull();
    }

    /** 写入最小公开资料，测试只聚焦同步幂等，不经由资料创建业务链路。 */
    private void insertApprovedResource(long resourceId) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 19, 0);
        jdbcTemplate.update("""
                INSERT INTO `resource` (
                    id, title, description, category_id, course_name, resource_type, tags,
                    file_id, uploader_id, status, view_count, download_count, favorite_count,
                    hot_score, created_at, updated_at
                )
                VALUES (?, ?, '同步幂等测试资料', 1, '同步幂等测试课程', ?, '同步,幂等',
                        ?, 1, ?, 0, 0, 0, ?, ?, ?)
                """,
                resourceId,
                "同步幂等资料-" + resourceId,
                Resource.TYPE_NOTE,
                resourceId,
                Resource.STATUS_APPROVED,
                BigDecimal.ZERO,
                now,
                now);
    }
}
