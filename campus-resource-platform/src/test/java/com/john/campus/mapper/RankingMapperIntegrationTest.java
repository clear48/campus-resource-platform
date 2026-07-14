package com.john.campus.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.config.MyBatisConfig;
import com.john.campus.entity.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * 排行榜 Mapper 集成测试：使用 H2 MySQL 模式执行真实 XML，校验 Redis 候选补齐、MySQL 兜底、游标扫描和热度快照写回。
 */
@MybatisTest
@Import(MyBatisConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:ranking_mapper_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Sql(scripts = "/sql/resource-db-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RankingMapperIntegrationTest {

    private static final long PRIMARY_CATEGORY_ID = 10L;
    private static final long SECONDARY_CATEGORY_ID = 20L;

    @Autowired
    private ResourceMapper resourceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReturnOnlyApprovedCandidatesForRedisRankingSupplement() {
        insertResource(101L, PRIMARY_CATEGORY_ID, Resource.STATUS_APPROVED, 20L, 3L, "100.00");
        insertResource(102L, PRIMARY_CATEGORY_ID, Resource.STATUS_PENDING_REVIEW, 99L, 99L, "999.00");
        insertResource(103L, SECONDARY_CATEGORY_ID, Resource.STATUS_APPROVED, 30L, 5L, "200.00");
        insertResource(104L, PRIMARY_CATEGORY_ID, Resource.STATUS_OFFLINE, 80L, 8L, "800.00");

        // Redis 候选顺序由 Service 恢复，Mapper 只负责阻止非公开或分类不匹配资料进入公开榜单。
        List<Resource> candidates = resourceMapper.selectApprovedRankingCandidatesByIds(
                List.of(104L, 103L, 102L, 101L), PRIMARY_CATEGORY_ID);

        assertThat(candidates).extracting(Resource::getId).containsExactly(101L);
        assertThat(candidates).extracting(Resource::getStatus).containsOnly(Resource.STATUS_APPROVED);
    }

    @Test
    void shouldUseStableMysqlFallbackOrderAndApprovedCursorScan() {
        insertResource(101L, PRIMARY_CATEGORY_ID, Resource.STATUS_APPROVED, 10L, 1L, "50.00");
        insertResource(102L, PRIMARY_CATEGORY_ID, Resource.STATUS_APPROVED, 30L, 2L, "50.00");
        insertResource(103L, PRIMARY_CATEGORY_ID, Resource.STATUS_APPROVED, 100L, 9L, "40.00");
        insertResource(104L, PRIMARY_CATEGORY_ID, Resource.STATUS_PENDING_REVIEW, 999L, 99L, "999.00");
        insertResource(105L, SECONDARY_CATEGORY_ID, Resource.STATUS_APPROVED, 200L, 20L, "900.00");

        // hot_score 相同时必须按下载量和 ID 稳定排序，避免 Redis 降级后首页榜单出现随机抖动。
        List<Resource> fallbackRanking = resourceMapper.selectHotApprovedResources(PRIMARY_CATEGORY_ID, 3);
        List<Resource> cursorBatch = resourceMapper.selectApprovedResourcesAfterId(101L, 2);

        assertThat(fallbackRanking).extracting(Resource::getId).containsExactly(102L, 101L, 103L);
        assertThat(cursorBatch).extracting(Resource::getId).containsExactly(102L, 103L);
        assertThat(cursorBatch).extracting(Resource::getStatus).containsOnly(Resource.STATUS_APPROVED);
    }

    @Test
    void shouldPersistHotScoreOnlyForApprovedResources() {
        insertResource(101L, PRIMARY_CATEGORY_ID, Resource.STATUS_APPROVED, 10L, 1L, "50.00");
        insertResource(102L, PRIMARY_CATEGORY_ID, Resource.STATUS_OFFLINE, 30L, 2L, "60.00");

        // 快照任务只允许更新仍公开的资料，防止下架记录被后台任务重新写入可见榜单数据。
        int approvedRows = resourceMapper.updateApprovedHotScore(101L, new BigDecimal("88.50"));
        int offlineRows = resourceMapper.updateApprovedHotScore(102L, new BigDecimal("99.00"));

        assertThat(approvedRows).isEqualTo(1);
        assertThat(offlineRows).isZero();
        assertThat(resourceMapper.selectById(101L).getHotScore()).isEqualByComparingTo("88.50");
        assertThat(resourceMapper.selectById(102L).getHotScore()).isEqualByComparingTo("60.00");
    }

    /**
     * 直接写入最小资料记录，聚焦验证排行榜 SQL 的 status、分类、排序和快照条件，而非资料创建业务。
     */
    private void insertResource(
            long id,
            long categoryId,
            int status,
            long downloadCount,
            long favoriteCount,
            String hotScore) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 10, 0).plusMinutes(id);
        jdbcTemplate.update("""
                INSERT INTO `resource` (
                    id, title, description, category_id, course_name, resource_type, tags,
                    file_id, uploader_id, status, view_count, download_count, favorite_count,
                    hot_score, created_at, updated_at
                )
                VALUES (?, ?, '排行榜测试资料', ?, '排行榜测试课程', ?, '排行榜,测试',
                        ?, 1, ?, 0, ?, ?, ?, ?, ?)
                """,
                id,
                "排行榜资料-" + id,
                categoryId,
                Resource.TYPE_NOTE,
                id,
                status,
                downloadCount,
                favoriteCount,
                new BigDecimal(hotScore),
                createdAt,
                createdAt);
    }
}
