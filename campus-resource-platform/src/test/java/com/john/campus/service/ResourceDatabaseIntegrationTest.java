package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.PageResult;
import com.john.campus.common.UserContextHolder;
import com.john.campus.config.MyBatisConfig;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.CategoryMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.ResourceServiceImpl;
import com.john.campus.vo.MyResourceVO;
import com.john.campus.vo.ResourceCreateVO;
import com.john.campus.vo.ResourceDetailVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/**
 * 资料模块数据库集成测试：使用 H2 MySQL 模式执行真实 MyBatis XML，验证写入和读取链路。
 */
@MybatisTest
@Import({MyBatisConfig.class, ResourceServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:resource_db_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Sql(scripts = "/sql/resource-db-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class ResourceDatabaseIntegrationTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long CATEGORY_ID = 10L;
    private static final long FILE_ID = 20L;

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceMapper resourceMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private FileInfoMapper fileInfoMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearUserContext() {
        // Service 创建资料依赖 ThreadLocal 登录上下文，测试结束必须清理，避免污染后续用例。
        UserContextHolder.clear();
    }

    @Test
    void createShouldInsertPendingResourceAndReadBackFromDatabase() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        mockLoginUser(CURRENT_USER_ID);

        ResourceCreateVO response = resourceService.create(buildCreateDTO(FILE_ID, CATEGORY_ID));

        assertThat(response.resourceId()).isNotNull();
        assertThat(response.fileId()).isEqualTo(FILE_ID);
        assertThat(response.status()).isEqualTo(Resource.STATUS_PENDING_REVIEW);

        Resource saved = resourceMapper.selectById(response.resourceId());
        assertThat(saved).isNotNull();
        assertThat(saved.getTitle()).isEqualTo("Java 期末复习课件");
        assertThat(saved.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(saved.getFileId()).isEqualTo(FILE_ID);
        assertThat(saved.getUploaderId()).isEqualTo(CURRENT_USER_ID);
        assertThat(saved.getStatus()).isEqualTo(Resource.STATUS_PENDING_REVIEW);
        assertThat(saved.getTags()).isEqualTo("Java,复习");
        assertThat(saved.getViewCount()).isZero();
        assertThat(saved.getDownloadCount()).isZero();
        assertThat(saved.getFavoriteCount()).isZero();
        assertThat(saved.getHotScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(resourceMapper.countActiveByUploaderAndFileId(CURRENT_USER_ID, FILE_ID)).isEqualTo(1L);
    }

    @Test
    void mapperAndServiceShouldReadApprovedPublicDetailOnly() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        insertFile(21L, 1);
        insertResource(100L, CURRENT_USER_ID, FILE_ID, CATEGORY_ID, Resource.STATUS_APPROVED,
                "已通过资料", LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, CURRENT_USER_ID, 21L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "待审核资料", LocalDateTime.of(2026, 1, 3, 10, 0));

        Resource approved = resourceMapper.selectPublicDetailById(100L);
        Resource pending = resourceMapper.selectPublicDetailById(101L);
        ResourceDetailVO detail = resourceService.getPublicDetail(100L);

        assertThat(approved).isNotNull();
        assertThat(pending).isNull();
        assertThat(detail.resourceId()).isEqualTo(100L);
        assertThat(detail.categoryName()).isEqualTo("计算机基础");
        assertThat(detail.status()).isEqualTo(Resource.STATUS_APPROVED);
        assertThatThrownBy(() -> resourceService.getPublicDetail(101L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_STATUS_INVALID.getCode());
    }

    @Test
    void mapperShouldPageMyResourcesAndCountByUploader() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        insertFile(21L, 1);
        insertFile(22L, 1);
        insertFile(23L, 1);
        insertResource(100L, CURRENT_USER_ID, FILE_ID, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "当前用户待审核资料", LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, CURRENT_USER_ID, 21L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "当前用户已通过资料", LocalDateTime.of(2026, 1, 4, 10, 0));
        insertResource(102L, CURRENT_USER_ID, 22L, CATEGORY_ID, Resource.STATUS_REJECTED,
                "当前用户已拒绝资料", LocalDateTime.of(2026, 1, 3, 10, 0));
        insertResource(103L, OTHER_USER_ID, 23L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "其他用户资料", LocalDateTime.of(2026, 1, 5, 10, 0));
        mockLoginUser(CURRENT_USER_ID);

        List<Resource> allRecords = resourceMapper.selectByUploader(CURRENT_USER_ID, null, 0, 10);
        List<Resource> approvedRecords = resourceMapper.selectByUploader(CURRENT_USER_ID, Resource.STATUS_APPROVED, 0, 10);
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(1);
        pageQuery.setPageSize(2);
        PageResult<MyResourceVO> myResources = resourceService.listMyResources(null, pageQuery);

        assertThat(allRecords).extracting(Resource::getId).containsExactly(101L, 102L, 100L);
        assertThat(approvedRecords).extracting(Resource::getId).containsExactly(101L);
        assertThat(resourceMapper.countByUploader(CURRENT_USER_ID, null)).isEqualTo(3L);
        assertThat(resourceMapper.countByUploader(CURRENT_USER_ID, Resource.STATUS_APPROVED)).isEqualTo(1L);
        assertThat(myResources.total()).isEqualTo(3L);
        assertThat(myResources.records()).hasSize(2);
        assertThat(myResources.records()).extracting(MyResourceVO::resourceId).containsExactly(101L, 102L);
    }

    @Test
    void mapperShouldPagePendingReviewsWithFilters() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        insertFile(21L, 1);
        insertFile(22L, 1);
        insertResource(100L, CURRENT_USER_ID, FILE_ID, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "较早待审核资料", LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, CURRENT_USER_ID, 21L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "较晚待审核资料", LocalDateTime.of(2026, 1, 3, 10, 0));
        insertResource(102L, OTHER_USER_ID, 22L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "其他用户待审核资料", LocalDateTime.of(2026, 1, 4, 10, 0));
        insertResource(103L, CURRENT_USER_ID, 23L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "已通过资料不进审核池", LocalDateTime.of(2026, 1, 5, 10, 0));

        List<Resource> records = resourceMapper.selectPendingReviews(
                "Java", Resource.TYPE_COURSEWARE, CURRENT_USER_ID, 0, 10);
        long total = resourceMapper.countPendingReviews("Java", Resource.TYPE_COURSEWARE, CURRENT_USER_ID);
        long typeMismatchTotal = resourceMapper.countPendingReviews("Java", Resource.TYPE_NOTE, CURRENT_USER_ID);

        assertThat(records).extracting(Resource::getId).containsExactly(100L, 101L);
        assertThat(records).extracting(Resource::getStatus).containsOnly(Resource.STATUS_PENDING_REVIEW);
        assertThat(total).isEqualTo(2L);
        assertThat(typeMismatchTotal).isZero();
    }

    @Test
    void mapperShouldSearchApprovedResourcesWithFiltersAndCount() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertCategory(CATEGORY_ID + 1, "通识课程", 1);
        insertFile(30L, 1);
        insertFile(31L, 1);
        insertFile(32L, 1);
        insertSearchResource(200L, CURRENT_USER_ID, 30L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "数据结构期末复习提纲", "覆盖排序、树和图", "数据结构", Resource.TYPE_NOTE,
                "数据结构,复习", 128L, 35L, "745.00", LocalDateTime.of(2026, 2, 1, 10, 0));
        insertSearchResource(201L, CURRENT_USER_ID, 31L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "数据结构未审核资料", "仍处于待审核状态", "数据结构", Resource.TYPE_NOTE,
                "数据结构,复习", 99L, 20L, "600.00", LocalDateTime.of(2026, 2, 2, 10, 0));
        insertSearchResource(202L, OTHER_USER_ID, 32L, CATEGORY_ID + 1, Resource.STATUS_APPROVED,
                "操作系统课堂笔记", "进程调度和内存管理", "操作系统", Resource.TYPE_NOTE,
                "操作系统,复习", 88L, 16L, "520.00", LocalDateTime.of(2026, 2, 3, 10, 0));

        List<Resource> records = resourceMapper.searchApprovedResources(
                "数据结构", CATEGORY_ID, "数据结构", Resource.TYPE_NOTE, "复习",
                "createdAt", "desc", 0, 10);
        long total = resourceMapper.countApprovedResources(
                "数据结构", CATEGORY_ID, "数据结构", Resource.TYPE_NOTE, "复习");

        assertThat(records).extracting(Resource::getId).containsExactly(200L);
        assertThat(records).extracting(Resource::getStatus).containsOnly(Resource.STATUS_APPROVED);
        assertThat(total).isEqualTo(1L);
    }

    @Test
    void mapperShouldSearchApprovedResourcesWithSafeSortAndPaging() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(30L, 1);
        insertFile(31L, 1);
        insertFile(32L, 1);
        insertSearchResource(200L, CURRENT_USER_ID, 30L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 基础课件", "集合和 IO", "Java 程序设计", Resource.TYPE_COURSEWARE,
                "Java,课件", 50L, 8L, "120.00", LocalDateTime.of(2026, 2, 1, 10, 0));
        insertSearchResource(201L, CURRENT_USER_ID, 31L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 并发笔记", "线程池和锁", "Java 程序设计", Resource.TYPE_NOTE,
                "Java,并发", 200L, 22L, "680.00", LocalDateTime.of(2026, 2, 2, 10, 0));
        insertSearchResource(202L, CURRENT_USER_ID, 32L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "Java 真题解析", "期末真题", "Java 程序设计", Resource.TYPE_EXAM,
                "Java,真题", 100L, 15L, "360.00", LocalDateTime.of(2026, 2, 3, 10, 0));

        List<Resource> topDownloads = resourceMapper.searchApprovedResources(
                null, null, null, null, null, "downloadCount", "desc", 0, 2);
        List<Resource> hotScoreAscending = resourceMapper.searchApprovedResources(
                null, null, null, null, null, "hotScore", "asc", 0, 3);
        List<Resource> fallbackSort = resourceMapper.searchApprovedResources(
                null, null, null, null, null, "download_count desc", "desc;drop", 0, 3);

        assertThat(topDownloads).extracting(Resource::getId).containsExactly(201L, 202L);
        assertThat(hotScoreAscending).extracting(Resource::getId).containsExactly(200L, 202L, 201L);
        // 非白名单排序参数应回退到 created_at DESC，验证 Mapper 不直接拼接前端原始排序值。
        assertThat(fallbackSort).extracting(Resource::getId).containsExactly(202L, 201L, 200L);
    }

    @Test
    void mapperShouldUpdateAuditStatusesOnlyFromExpectedPreviousStatus() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        insertFile(21L, 1);
        insertFile(22L, 1);
        insertFile(23L, 1);
        insertResource(100L, CURRENT_USER_ID, FILE_ID, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "待通过资料", LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, CURRENT_USER_ID, 21L, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "待拒绝资料", LocalDateTime.of(2026, 1, 3, 10, 0));
        insertResource(102L, CURRENT_USER_ID, 22L, CATEGORY_ID, Resource.STATUS_APPROVED,
                "待下架资料", LocalDateTime.of(2026, 1, 4, 10, 0));
        insertResource(103L, CURRENT_USER_ID, 23L, CATEGORY_ID, Resource.STATUS_REJECTED,
                "非法状态资料", LocalDateTime.of(2026, 1, 5, 10, 0));
        LocalDateTime approvedAt = LocalDateTime.of(2026, 1, 6, 9, 0);
        LocalDateTime offlineAt = LocalDateTime.of(2026, 1, 7, 9, 0);

        int approveRows = resourceMapper.approvePendingReview(100L, approvedAt);
        int rejectRows = resourceMapper.rejectPendingReview(101L, "内容不完整");
        int offlineRows = resourceMapper.offlineApprovedResource(102L, "版权风险", offlineAt);
        int invalidApproveRows = resourceMapper.approvePendingReview(103L, approvedAt);
        int invalidOfflineRows = resourceMapper.offlineApprovedResource(101L, "重复下架", offlineAt);

        Resource approved = resourceMapper.selectById(100L);
        Resource rejected = resourceMapper.selectById(101L);
        Resource offline = resourceMapper.selectById(102L);
        Resource unchanged = resourceMapper.selectById(103L);

        assertThat(approveRows).isEqualTo(1);
        assertThat(rejectRows).isEqualTo(1);
        assertThat(offlineRows).isEqualTo(1);
        assertThat(invalidApproveRows).isZero();
        assertThat(invalidOfflineRows).isZero();
        assertThat(approved.getStatus()).isEqualTo(Resource.STATUS_APPROVED);
        assertThat(approved.getApprovedAt()).isEqualTo(approvedAt);
        assertThat(rejected.getStatus()).isEqualTo(Resource.STATUS_REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("内容不完整");
        assertThat(offline.getStatus()).isEqualTo(Resource.STATUS_OFFLINE);
        assertThat(offline.getOfflineReason()).isEqualTo("版权风险");
        assertThat(offline.getOfflineAt()).isEqualTo(offlineAt);
        assertThat(unchanged.getStatus()).isEqualTo(Resource.STATUS_REJECTED);
    }

    @Test
    void createShouldRejectDuplicateActiveResourceFromDatabase() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        insertResource(100L, CURRENT_USER_ID, FILE_ID, CATEGORY_ID, Resource.STATUS_PENDING_REVIEW,
                "已有待审核资料", LocalDateTime.of(2026, 1, 2, 10, 0));
        mockLoginUser(CURRENT_USER_ID);

        assertThatThrownBy(() -> resourceService.create(buildCreateDTO(FILE_ID, CATEGORY_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.DATA_DUPLICATE.getCode());
        assertThat(resourceMapper.countActiveByUploaderAndFileId(CURRENT_USER_ID, FILE_ID)).isEqualTo(1L);
    }

    @Test
    void createShouldRejectDeletedFileAndDisabledCategoryFromDatabase() {
        insertCategory(CATEGORY_ID, "计算机基础", 0);
        insertFile(FILE_ID, 2);
        mockLoginUser(CURRENT_USER_ID);

        assertThat(categoryMapper.selectEnabledById(CATEGORY_ID)).isNull();
        assertThat(fileInfoMapper.selectNormalById(FILE_ID)).isNull();
        assertThatThrownBy(() -> resourceService.create(buildCreateDTO(FILE_ID, CATEGORY_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM `resource`", Long.class)).isZero();
    }

    @Test
    void createShouldRejectNormalFileWithoutCurrentUserAuthorization() {
        insertCategory(CATEGORY_ID, "计算机基础", 1);
        insertFile(FILE_ID, 1);
        mockLoginUser(OTHER_USER_ID);

        assertThatThrownBy(() -> resourceService.create(buildCreateDTO(FILE_ID, CATEGORY_ID)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM `resource`", Long.class)).isZero();
    }

    private void mockLoginUser(long userId) {
        UserContextHolder.set(new LoginUser(userId, 1, "resource-db-test-jti"));
    }

    private ResourceCreateDTO buildCreateDTO(long fileId, long categoryId) {
        ResourceCreateDTO dto = new ResourceCreateDTO();
        dto.setFileId(fileId);
        dto.setTitle("Java 期末复习课件");
        dto.setDescription("覆盖集合、IO 和并发基础");
        dto.setCategoryId(categoryId);
        dto.setCourseName("Java 程序设计");
        dto.setResourceType(Resource.TYPE_COURSEWARE);
        dto.setTags(List.of("Java", "复习", "Java"));
        return dto;
    }

    private void insertCategory(long id, String categoryName, int status) {
        jdbcTemplate.update("""
                INSERT INTO category (id, parent_id, category_name, description, sort_order, status)
                VALUES (?, 0, ?, '测试分类', 0, ?)
                """, id, categoryName, status);
    }

    private void insertFile(long id, int status) {
        jdbcTemplate.update("""
                INSERT INTO file_info (
                    id, file_md5, original_name, stored_name, file_ext, mime_type,
                    file_size, storage_type, storage_path, uploader_id, ref_count, status
                )
                VALUES (?, ?, ?, ?, 'pdf', 'application/pdf', ?, 1, ?, ?, 1, ?)
                """,
                id,
                String.format("%032d", id),
                "resource-" + id + ".pdf",
                "resource-" + id + "-stored.pdf",
                1024L + id,
                "/tmp/resource-" + id + ".pdf",
                CURRENT_USER_ID,
                status);
        // 测试夹具模拟首次上传完成后的授权关系；跨用户用例会切换登录用户验证隔离。
        jdbcTemplate.update("""
                INSERT INTO user_file_authorization (user_id, file_id, source_type)
                VALUES (?, ?, 1)
                """, CURRENT_USER_ID, id);
    }

    private void insertResource(
            long id,
            long uploaderId,
            long fileId,
            long categoryId,
            int status,
            String title,
            LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO `resource` (
                    id, title, description, category_id, course_name, resource_type, tags,
                    file_id, uploader_id, status, view_count, download_count, favorite_count,
                    hot_score, created_at, updated_at
                )
                VALUES (?, ?, '测试资料简介', ?, 'Java 程序设计', ?, 'Java,复习',
                        ?, ?, ?, 0, 0, 0, 0.00, ?, ?)
                """,
                id,
                title,
                categoryId,
                Resource.TYPE_COURSEWARE,
                fileId,
                uploaderId,
                status,
                createdAt,
                createdAt);
    }

    private void insertSearchResource(
            long id,
            long uploaderId,
            long fileId,
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
                fileId,
                uploaderId,
                status,
                downloadCount,
                favoriteCount,
                new BigDecimal(hotScore),
                createdAt,
                createdAt);
    }
}
