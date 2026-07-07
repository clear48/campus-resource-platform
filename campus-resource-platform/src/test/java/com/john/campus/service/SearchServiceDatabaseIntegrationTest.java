package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.PageResult;
import com.john.campus.config.MyBatisConfig;
import com.john.campus.dto.SearchResourceQueryDTO;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.SearchServiceImpl;
import com.john.campus.vo.SearchResourceVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * 搜索 Service 数据库集成测试：执行真实 MyBatis XML，验证 Service 到 Mapper 的公开搜索链路。
 */
@MybatisTest
@Import({MyBatisConfig.class, SearchServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:search_service_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Sql(scripts = "/sql/resource-db-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SearchServiceDatabaseIntegrationTest {

    private static final long CATEGORY_ID = 10L;
    private static final long OTHER_CATEGORY_ID = 11L;
    private static final long UPLOADER_ID = 1L;

    @Autowired
    private SearchService searchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchResourcesShouldTrimFiltersAndReturnApprovedPage() {
        insertSearchResource(200L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "数据结构期末复习提纲", "覆盖排序、树和图", "数据结构", Resource.TYPE_NOTE,
                "数据结构,复习", 128L, 35L, "745.00", LocalDateTime.of(2026, 2, 1, 10, 0));
        insertSearchResource(201L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "数据结构未审核资料", "仍处于待审核状态", "数据结构", Resource.TYPE_NOTE,
                "数据结构,复习", 99L, 20L, "600.00", LocalDateTime.of(2026, 2, 2, 10, 0));
        insertSearchResource(202L, OTHER_CATEGORY_ID, Resource.STATUS_APPROVED,
                "操作系统课堂笔记", "进程调度和内存管理", "操作系统", Resource.TYPE_NOTE,
                "操作系统,复习", 88L, 16L, "520.00", LocalDateTime.of(2026, 2, 3, 10, 0));
        SearchResourceQueryDTO query = new SearchResourceQueryDTO();
        query.setKeyword(" 数据结构 ");
        query.setCategoryId(CATEGORY_ID);
        query.setCourseName(" 数据结构 ");
        query.setResourceType(Resource.TYPE_NOTE);
        query.setTag(" 复习 ");
        query.setSortBy("hotScore");
        query.setOrder("DESC");
        query.setPageNo(1);
        query.setPageSize(10);

        PageResult<SearchResourceVO> result = searchService.searchResources(query);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.pages()).isEqualTo(1L);
        assertThat(result.records()).hasSize(1);
        SearchResourceVO record = result.records().get(0);
        assertThat(record.resourceId()).isEqualTo(200L);
        assertThat(record.tags()).containsExactly("数据结构", "复习");
        assertThat(record.hotScore()).isEqualByComparingTo("745.00");
    }

    @Test
    void searchResourcesShouldUseDefaultQueryAndConvertRecords() {
        insertSearchResource(200L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 基础课件", "集合和 IO", "Java 程序设计", Resource.TYPE_COURSEWARE,
                "Java,课件", 50L, 8L, "120.00", LocalDateTime.of(2026, 2, 1, 10, 0));
        insertSearchResource(201L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 并发笔记", "线程池和锁", "Java 程序设计", Resource.TYPE_NOTE,
                "Java,并发", 200L, 22L, "680.00", LocalDateTime.of(2026, 2, 2, 10, 0));
        insertSearchResource(202L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 真题解析", "期末真题", "Java 程序设计", Resource.TYPE_EXAM,
                "Java,真题", 100L, 15L, "360.00", LocalDateTime.of(2026, 2, 3, 10, 0));

        PageResult<SearchResourceVO> result = searchService.searchResources(null);

        assertThat(result.pageNo()).isEqualTo(1L);
        assertThat(result.pageSize()).isEqualTo(10L);
        assertThat(result.total()).isEqualTo(3L);
        assertThat(result.records()).extracting(SearchResourceVO::resourceId).containsExactly(202L, 201L, 200L);
        assertThat(result.records().get(0).downloadCount()).isEqualTo(100L);
    }

    @Test
    void searchResourcesShouldRejectInvalidParameters() {
        SearchResourceQueryDTO invalidPageNo = new SearchResourceQueryDTO();
        invalidPageNo.setPageNo(0);
        assertParamError(invalidPageNo);

        SearchResourceQueryDTO invalidPageSize = new SearchResourceQueryDTO();
        invalidPageSize.setPageSize(101);
        assertParamError(invalidPageSize);

        SearchResourceQueryDTO invalidResourceType = new SearchResourceQueryDTO();
        invalidResourceType.setResourceType(6);
        assertParamError(invalidResourceType);

        SearchResourceQueryDTO invalidSortBy = new SearchResourceQueryDTO();
        invalidSortBy.setSortBy("id");
        assertParamError(invalidSortBy);

        SearchResourceQueryDTO invalidOrder = new SearchResourceQueryDTO();
        invalidOrder.setOrder("drop");
        assertParamError(invalidOrder);
    }

    private void assertParamError(SearchResourceQueryDTO query) {
        assertThatThrownBy(() -> searchService.searchResources(query))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    private void insertSearchResource(
            long id,
            long categoryId,
            int status,
            String title,
            String description,
            String courseName,
            int resourceType,
            String tags,
            long downloadCount,
            long favoriteCount,
            String hotScore,
            LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO `resource` (
                    id, title, description, category_id, course_name, resource_type, tags,
                    file_id, uploader_id, status, view_count, download_count, favorite_count,
                    hot_score, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
                """,
                id,
                title,
                description,
                categoryId,
                courseName,
                resourceType,
                tags,
                id + 1000,
                UPLOADER_ID,
                status,
                downloadCount,
                favoriteCount,
                new BigDecimal(hotScore),
                createdAt,
                createdAt);
    }
}
