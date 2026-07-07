package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.PageResult;
import com.john.campus.common.UserContextHolder;
import com.john.campus.config.MyBatisConfig;
import com.john.campus.dto.AuditApproveDTO;
import com.john.campus.dto.AuditRejectDTO;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.entity.AuditRecord;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.AuditRecordMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.AuditServiceImpl;
import com.john.campus.vo.AuditRecordVO;
import com.john.campus.vo.AuditResultVO;
import com.john.campus.vo.PendingReviewResourceVO;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核服务数据库集成测试：使用本机 MySQL 执行真实 MyBatis XML，验证状态机、事务和审核记录留痕。
 */
@MybatisTest
@Import({MyBatisConfig.class, AuditServiceImpl.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=${MYSQL_TEST_URL:jdbc:mysql://localhost:3306/campus_resource_platform_audit_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true}",
        "spring.datasource.username=${MYSQL_TEST_USERNAME:${MYSQL_USERNAME:root}}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:${MYSQL_PASSWORD:}}",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql(scripts = "/sql/resource-db-test-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AuditServiceDatabaseIntegrationTest {

    private static final long ADMIN_USER_ID = 90001L;
    private static final long STUDENT_USER_ID = 10001L;
    private static final long CATEGORY_ID = 10L;

    @Autowired
    private AuditService auditService;

    @Autowired
    private ResourceMapper resourceMapper;

    @Autowired
    private AuditRecordMapper auditRecordMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearUserContext() {
        // 审核服务依赖登录上下文判断管理员权限，测试后必须清理 ThreadLocal。
        UserContextHolder.clear();
    }

    @Test
    void listPendingReviewsShouldRequireAdminAndReturnFilteredPage() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_PENDING_REVIEW,
                "Java 待审核资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, STUDENT_USER_ID, 201L, Resource.STATUS_APPROVED,
                "Java 已通过资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 3, 10, 0));
        insertResource(102L, 10002L, 202L, Resource.STATUS_PENDING_REVIEW,
                "算法待审核资料", Resource.TYPE_NOTE, LocalDateTime.of(2026, 1, 4, 10, 0));
        mockAdmin();

        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNo(1);
        pageQuery.setPageSize(10);
        PageResult<PendingReviewResourceVO> page = auditService.listPendingReviews(
                "Java", Resource.TYPE_COURSEWARE, STUDENT_USER_ID, pageQuery);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).resourceId()).isEqualTo(100L);
        assertThat(page.records().get(0).status()).isEqualTo(Resource.STATUS_PENDING_REVIEW);

        mockStudent();
        assertThatThrownBy(() -> auditService.listPendingReviews(null, null, null, pageQuery))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void auditEntriesShouldRejectStudentRoleBeforeBusinessAccess() {
        mockStudent();
        AuditRejectDTO rejectDTO = new AuditRejectDTO();
        rejectDTO.setRejectReason("内容不完整");
        ResourceOfflineDTO offlineDTO = new ResourceOfflineDTO();
        offlineDTO.setOfflineReason("版权风险");

        // 管理员边界必须在状态机和数据库访问前统一生效，避免普通用户探测资料状态。
        assertForbidden(() -> auditService.listPendingReviews(null, null, null, new PageQuery()));
        assertForbidden(() -> auditService.approve(100L, new AuditApproveDTO()));
        assertForbidden(() -> auditService.reject(100L, rejectDTO));
        assertForbidden(() -> auditService.offline(100L, offlineDTO));
        assertForbidden(() -> auditService.listAuditRecords(100L));
    }

    @Test
    void approveShouldUpdateResourceAndInsertAuditRecord() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_PENDING_REVIEW,
                "Java 待通过资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        mockAdmin();
        AuditApproveDTO dto = new AuditApproveDTO();
        dto.setAuditReason("资料内容完整");

        AuditResultVO result = auditService.approve(100L, dto);

        Resource resource = resourceMapper.selectById(100L);
        List<AuditRecord> records = auditRecordMapper.selectByResourceId(100L);
        assertThat(result.resourceId()).isEqualTo(100L);
        assertThat(result.actionType()).isEqualTo(AuditRecord.ACTION_APPROVE);
        assertThat(result.beforeStatus()).isEqualTo(Resource.STATUS_PENDING_REVIEW);
        assertThat(result.afterStatus()).isEqualTo(Resource.STATUS_APPROVED);
        assertThat(result.auditRecordId()).isNotNull();
        assertThat(result.approvedAt()).isNotNull();
        assertThat(resource.getStatus()).isEqualTo(Resource.STATUS_APPROVED);
        assertThat(resource.getApprovedAt()).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getAuditorId()).isEqualTo(ADMIN_USER_ID);
        assertThat(records.get(0).getAuditReason()).isEqualTo("资料内容完整");
    }

    @Test
    void rejectShouldUpdateReasonAndInsertAuditRecord() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_PENDING_REVIEW,
                "Java 待拒绝资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        mockAdmin();
        AuditRejectDTO dto = new AuditRejectDTO();
        dto.setRejectReason("内容与课程无关");

        AuditResultVO result = auditService.reject(100L, dto);

        Resource resource = resourceMapper.selectById(100L);
        List<AuditRecord> records = auditRecordMapper.selectByResourceId(100L);
        assertThat(result.actionType()).isEqualTo(AuditRecord.ACTION_REJECT);
        assertThat(result.afterStatus()).isEqualTo(Resource.STATUS_REJECTED);
        assertThat(resource.getStatus()).isEqualTo(Resource.STATUS_REJECTED);
        assertThat(resource.getRejectReason()).isEqualTo("内容与课程无关");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getBeforeStatus()).isEqualTo(Resource.STATUS_PENDING_REVIEW);
        assertThat(records.get(0).getAfterStatus()).isEqualTo(Resource.STATUS_REJECTED);
    }

    @Test
    void offlineShouldUpdateApprovedResourceAndInsertAuditRecord() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_APPROVED,
                "Java 待下架资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        mockAdmin();
        ResourceOfflineDTO dto = new ResourceOfflineDTO();
        dto.setOfflineReason("版权风险");

        AuditResultVO result = auditService.offline(100L, dto);

        Resource resource = resourceMapper.selectById(100L);
        List<AuditRecord> records = auditRecordMapper.selectByResourceId(100L);
        assertThat(result.actionType()).isEqualTo(AuditRecord.ACTION_OFFLINE);
        assertThat(result.afterStatus()).isEqualTo(Resource.STATUS_OFFLINE);
        assertThat(result.offlineAt()).isNotNull();
        assertThat(resource.getStatus()).isEqualTo(Resource.STATUS_OFFLINE);
        assertThat(resource.getOfflineReason()).isEqualTo("版权风险");
        assertThat(resource.getOfflineAt()).isNotNull();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getActionType()).isEqualTo(AuditRecord.ACTION_OFFLINE);
    }

    @Test
    void auditActionsShouldRejectInvalidStateAndMissingReason() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_APPROVED,
                "Java 已通过资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        insertResource(101L, STUDENT_USER_ID, 201L, Resource.STATUS_PENDING_REVIEW,
                "Java 待审核资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 3, 10, 0));
        mockAdmin();
        AuditRejectDTO blankReject = new AuditRejectDTO();
        blankReject.setRejectReason(" ");

        assertThatThrownBy(() -> auditService.approve(100L, new AuditApproveDTO()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_STATUS_INVALID.getCode());
        assertThatThrownBy(() -> auditService.reject(101L, blankReject))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM audit_record", Long.class)).isZero();
    }

    @Test
    void auditActionsShouldRejectMissingResource() {
        mockAdmin();

        assertThatThrownBy(() -> auditService.approve(999L, new AuditApproveDTO()))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThatThrownBy(() -> auditService.listAuditRecords(999L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
    }

    @Test
    void approveShouldRollbackResourceUpdateWhenAuditRecordInsertFails() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_PENDING_REVIEW,
                "Java 待回滚资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        // 构造缺失管理员 ID 的登录态，让 resource 更新成功后在 audit_record NOT NULL 约束处失败。
        UserContextHolder.set(new LoginUser(null, 2, "audit-admin-missing-id-jti"));

        assertThatThrownBy(() -> auditService.approve(100L, new AuditApproveDTO()))
                .isInstanceOf(RuntimeException.class);

        Resource resource = resourceMapper.selectById(100L);
        assertThat(resource.getStatus()).isEqualTo(Resource.STATUS_PENDING_REVIEW);
        assertThat(resource.getApprovedAt()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM audit_record", Long.class)).isZero();
    }

    @Test
    void listAuditRecordsShouldReturnAuditHistory() {
        insertResource(100L, STUDENT_USER_ID, 200L, Resource.STATUS_PENDING_REVIEW,
                "Java 审核历史资料", Resource.TYPE_COURSEWARE, LocalDateTime.of(2026, 1, 2, 10, 0));
        mockAdmin();
        AuditRejectDTO dto = new AuditRejectDTO();
        dto.setRejectReason("内容不完整");
        auditService.reject(100L, dto);

        List<AuditRecordVO> records = auditService.listAuditRecords(100L);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).resourceId()).isEqualTo(100L);
        assertThat(records.get(0).auditorId()).isEqualTo(ADMIN_USER_ID);
        assertThat(records.get(0).actionType()).isEqualTo(AuditRecord.ACTION_REJECT);
        assertThat(records.get(0).auditReason()).isEqualTo("内容不完整");
        assertThat(records.get(0).createdAt()).isNotNull();
    }

    private void mockAdmin() {
        UserContextHolder.set(new LoginUser(ADMIN_USER_ID, 2, "audit-admin-jti"));
    }

    private void mockStudent() {
        UserContextHolder.set(new LoginUser(STUDENT_USER_ID, 1, "audit-student-jti"));
    }

    private void assertForbidden(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    private void insertResource(
            long id,
            long uploaderId,
            long fileId,
            int status,
            String title,
            int resourceType,
            LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO `resource` (
                    id, title, description, category_id, course_name, resource_type, tags,
                    file_id, uploader_id, status, view_count, download_count, favorite_count,
                    hot_score, created_at, updated_at
                )
                VALUES (?, ?, '测试资料简介', ?, 'Java 程序设计', ?, 'Java,审核',
                        ?, ?, ?, 0, 0, 0, 0.00, ?, ?)
                """,
                id,
                title,
                CATEGORY_ID,
                resourceType,
                fileId,
                uploaderId,
                status,
                createdAt,
                createdAt);
    }
}
