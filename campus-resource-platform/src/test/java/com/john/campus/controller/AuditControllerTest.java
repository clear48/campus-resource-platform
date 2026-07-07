package com.john.campus.controller;

import static com.john.campus.interceptor.JwtAuthenticationInterceptor.AUTHORIZATION_HEADER;
import static com.john.campus.interceptor.JwtAuthenticationInterceptor.BEARER_PREFIX;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.JwtClaims;
import com.john.campus.common.JwtUtils;
import com.john.campus.common.PageResult;
import com.john.campus.config.WebMvcConfig;
import com.john.campus.dto.AuditRejectDTO;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.entity.AuditRecord;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.exception.GlobalExceptionHandler;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.AuditService;
import com.john.campus.vo.AuditRecordVO;
import com.john.campus.vo.AuditResultVO;
import com.john.campus.vo.PendingReviewResourceVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 审核接口层测试：覆盖管理员接口路由、JWT 保护、参数绑定和请求体验证。
 */
@WebMvcTest(AuditController.class)
@Import({WebMvcConfig.class, JwtAuthenticationInterceptor.class, GlobalExceptionHandler.class})
class AuditControllerTest {

    /**
     * 测试 Token 固定为短字符串，真实签名解析由 mock 的 JwtUtils 接管。
     */
    private static final String TEST_TOKEN = "audit-controller-test-token";
    /**
     * 普通学生 Token 用于确认管理端路径不会只校验“已登录”就放行。
     */
    private static final String STUDENT_TEST_TOKEN = "audit-controller-student-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpLoginToken() {
        // 审核接口必须走真实 JWT 拦截器；这里 mock 管理员身份，专注验证 Controller 层。
        when(jwtUtils.parseToken(TEST_TOKEN)).thenReturn(new JwtClaims(
                90001L,
                2,
                "audit-controller-test-jti",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1)
        ));
        when(jwtUtils.parseToken(STUDENT_TEST_TOKEN)).thenReturn(new JwtClaims(
                10001L,
                1,
                "audit-controller-student-jti",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1)
        ));
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @Test
    void listPendingReviewsShouldBindQueryAndPage() throws Exception {
        PageResult<PendingReviewResourceVO> page = PageResult.of(
                List.of(new PendingReviewResourceVO(
                        100L,
                        "Java 待审核资料",
                        "测试资料简介",
                        10L,
                        "Java 程序设计",
                        Resource.TYPE_COURSEWARE,
                        List.of("Java", "审核"),
                        200L,
                        10001L,
                        Resource.STATUS_PENDING_REVIEW,
                        LocalDateTime.now()
                )),
                2,
                5,
                6
        );
        when(auditService.listPendingReviews(eq("Java"), eq(Resource.TYPE_COURSEWARE), eq(10001L), any(PageQuery.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/admin/resources/pending-reviews")
                        .with(bearerToken())
                        .param("courseName", "Java")
                        .param("resourceType", String.valueOf(Resource.TYPE_COURSEWARE))
                        .param("uploaderId", "10001")
                        .param("pageNo", "2")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.records[0].resourceId").value(100));

        ArgumentCaptor<PageQuery> pageQueryCaptor = ArgumentCaptor.forClass(PageQuery.class);
        verify(auditService).listPendingReviews(eq("Java"), eq(Resource.TYPE_COURSEWARE), eq(10001L), pageQueryCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(2, pageQueryCaptor.getValue().getPageNo());
        org.junit.jupiter.api.Assertions.assertEquals(5, pageQueryCaptor.getValue().getPageSize());
    }

    @Test
    void auditEndpointsShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-approvals", 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(auditService, never()).approve(any(), any());
    }

    @Test
    void auditEndpointsShouldRejectStudentRole() throws Exception {
        // Controller 经过真实 JWT 拦截器进入业务层后，管理员角色边界由 Service 统一兜底。
        when(auditService.approve(eq(100L), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-approvals", 100L)
                        .with(studentBearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));

        verify(auditService).approve(eq(100L), any());
    }

    @Test
    void approveShouldAllowEmptyBodyAndReturnResult() throws Exception {
        when(auditService.approve(eq(100L), any()))
                .thenReturn(buildAuditResultVO(AuditRecord.ACTION_APPROVE, Resource.STATUS_APPROVED));

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-approvals", 100L)
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resourceId").value(100))
                .andExpect(jsonPath("$.data.afterStatus").value(Resource.STATUS_APPROVED));

        verify(auditService).approve(eq(100L), any());
    }

    @Test
    void approveShouldMapResourceNotFoundException() throws Exception {
        when(auditService.approve(eq(404L), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在"));

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-approvals", 404L)
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.getCode()));
    }

    @Test
    void approveShouldMapInvalidResourceStateException() throws Exception {
        when(auditService.approve(eq(101L), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料状态已变化，无法审核通过"));

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-approvals", 101L)
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_STATUS_INVALID.getCode()));
    }

    @Test
    void rejectShouldValidateReasonBeforeCallingService() throws Exception {
        String invalidBody = """
                {
                  "rejectReason": ""
                }
                """;

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/audit-rejections", 100L)
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        verify(auditService, never()).reject(any(), any(AuditRejectDTO.class));
    }

    @Test
    void offlineShouldBindBodyAndReturnResult() throws Exception {
        when(auditService.offline(eq(100L), any(ResourceOfflineDTO.class)))
                .thenReturn(buildAuditResultVO(AuditRecord.ACTION_OFFLINE, Resource.STATUS_OFFLINE));
        String body = """
                {
                  "offlineReason": "版权风险"
                }
                """;

        mockMvc.perform(post("/api/v1/admin/resources/{resourceId}/offline-records", 100L)
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.actionType").value(AuditRecord.ACTION_OFFLINE))
                .andExpect(jsonPath("$.data.afterStatus").value(Resource.STATUS_OFFLINE));
    }

    @Test
    void listAuditRecordsShouldReturnHistory() throws Exception {
        when(auditService.listAuditRecords(100L)).thenReturn(List.of(new AuditRecordVO(
                500L,
                100L,
                90001L,
                AuditRecord.ACTION_REJECT,
                Resource.STATUS_PENDING_REVIEW,
                Resource.STATUS_REJECTED,
                "内容不完整",
                LocalDateTime.now()
        )));

        mockMvc.perform(get("/api/v1/admin/resources/{resourceId}/audit-records", 100L)
                        .with(bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].auditRecordId").value(500))
                .andExpect(jsonPath("$.data[0].actionType").value(AuditRecord.ACTION_REJECT));
    }

    private RequestPostProcessor bearerToken() {
        return bearerToken(TEST_TOKEN);
    }

    private RequestPostProcessor studentBearerToken() {
        return bearerToken(STUDENT_TEST_TOKEN);
    }

    private RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
            return request;
        };
    }

    private AuditResultVO buildAuditResultVO(int actionType, int afterStatus) {
        return new AuditResultVO(
                100L,
                actionType,
                Resource.STATUS_PENDING_REVIEW,
                afterStatus,
                500L,
                null,
                actionType == AuditRecord.ACTION_APPROVE ? LocalDateTime.now() : null,
                actionType == AuditRecord.ACTION_OFFLINE ? LocalDateTime.now() : null
        );
    }
}
