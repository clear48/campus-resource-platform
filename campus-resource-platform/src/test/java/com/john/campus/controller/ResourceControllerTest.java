package com.john.campus.controller;

import static com.john.campus.interceptor.JwtAuthenticationInterceptor.AUTHORIZATION_HEADER;
import static com.john.campus.interceptor.JwtAuthenticationInterceptor.BEARER_PREFIX;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.exception.BusinessException;
import com.john.campus.exception.GlobalExceptionHandler;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.ResourceService;
import com.john.campus.vo.MyResourceVO;
import com.john.campus.vo.ResourceCreateVO;
import com.john.campus.vo.ResourceDetailVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 资料接口层测试：重点覆盖 Controller 参数绑定、统一响应和 WebMvcConfig 的鉴权路径配置。
 */
@WebMvcTest(ResourceController.class)
@Import({WebMvcConfig.class, JwtAuthenticationInterceptor.class, GlobalExceptionHandler.class})
class ResourceControllerTest {

    /**
     * 测试 Token 固定为一个短字符串，JWT 真实性由 mock 的 JwtUtils 负责模拟。
     */
    private static final String TEST_TOKEN = "resource-controller-test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResourceService resourceService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpLoginToken() {
        // 受保护接口会经过真实拦截器，这里只 mock Token 解析结果，避免测试依赖真实签名密钥。
        when(jwtUtils.parseToken(TEST_TOKEN)).thenReturn(new JwtClaims(
                1L,
                1,
                "resource-controller-test-jti",
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1)
        ));
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(false);
    }

    @Test
    void publicDetailShouldAllowAnonymousAccess() throws Exception {
        when(resourceService.getPublicDetail(100L)).thenReturn(buildResourceDetailVO());

        mockMvc.perform(get("/api/v1/resources/{resourceId}", 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resourceId").value(100))
                .andExpect(jsonPath("$.data.status").value(1));

        // 公开详情路径应被 WebMvcConfig 放行，不应触发 JWT 解析。
        verify(jwtUtils, never()).parseToken(anyString());
    }

    @Test
    void createShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(resourceService, never()).create(any(ResourceCreateDTO.class));
    }

    @Test
    void createShouldValidateRequestBodyBeforeCallingService() throws Exception {
        String invalidBody = """
                {
                  "fileId": 200,
                  "title": "",
                  "categoryId": 10,
                  "courseName": "Java 程序设计",
                  "resourceType": 1
                }
                """;

        mockMvc.perform(post("/api/v1/resources")
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        // 参数校验失败时不进入业务层，避免 Service 处理明显无效的请求体。
        verify(resourceService, never()).create(any(ResourceCreateDTO.class));
    }

    @Test
    void createShouldReturnPendingReviewResource() throws Exception {
        when(resourceService.create(any(ResourceCreateDTO.class)))
                .thenReturn(new ResourceCreateVO(300L, 200L, 0, "PENDING_REVIEW", "资料已创建，等待管理员审核"));

        mockMvc.perform(post("/api/v1/resources")
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resourceId").value(300))
                .andExpect(jsonPath("$.data.fileId").value(200))
                .andExpect(jsonPath("$.data.status").value(0));

        verify(resourceService).create(argThat(request ->
                request.getFileId().equals(200L)
                        && request.getCategoryId().equals(10L)
                        && request.getResourceType().equals(1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createBusinessExceptions")
    void createShouldMapBusinessExceptions(
            String caseName,
            ErrorCode errorCode,
            String message,
            HttpStatus expectedHttpStatus) throws Exception {
        when(resourceService.create(any(ResourceCreateDTO.class)))
                .thenThrow(new BusinessException(errorCode, message));

        mockMvc.perform(post("/api/v1/resources")
                        .with(bearerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().is(expectedHttpStatus.value()))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()))
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void publicDetailShouldReturnStatusInvalidWhenResourceIsInvisible() throws Exception {
        when(resourceService.getPublicDetail(101L))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料未审核通过或已下架"));

        mockMvc.perform(get("/api/v1/resources/{resourceId}", 101L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_STATUS_INVALID.getCode()));

        // 未审核资料的不可见性由 Service 判断，但公开路径仍然不应要求登录。
        verify(jwtUtils, never()).parseToken(anyString());
    }

    @Test
    void listMyResourcesShouldBindStatusAndPageQuery() throws Exception {
        PageResult<MyResourceVO> pageResult = PageResult.of(
                List.of(new MyResourceVO(300L, "Java 课件", "Java 程序设计", 0, null, null, LocalDateTime.now())),
                2,
                5,
                6
        );
        when(resourceService.listMyResources(eq(0), any(PageQuery.class))).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/users/me/resources")
                        .with(bearerToken())
                        .param("status", "0")
                        .param("pageNo", "2")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.records[0].resourceId").value(300));

        ArgumentCaptor<PageQuery> pageQueryCaptor = ArgumentCaptor.forClass(PageQuery.class);
        verify(resourceService).listMyResources(eq(0), pageQueryCaptor.capture());
        PageQuery pageQuery = pageQueryCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(2, pageQuery.getPageNo());
        org.junit.jupiter.api.Assertions.assertEquals(5, pageQuery.getPageSize());
    }

    @Test
    void listMyResourcesShouldRejectInvalidPageQuery() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/resources")
                        .with(bearerToken())
                        .param("pageNo", "0")
                        .param("pageSize", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        verify(resourceService, never()).listMyResources(any(), any(PageQuery.class));
    }

    @Test
    void listMyResourcesShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/resources")
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(resourceService, never()).listMyResources(any(), any(PageQuery.class));
    }

    static Stream<Arguments> createBusinessExceptions() {
        return Stream.of(
                Arguments.of("文件不存在或已删除", ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已删除", HttpStatus.NOT_FOUND),
                Arguments.of("分类不存在或已禁用", ErrorCode.RESOURCE_NOT_FOUND, "分类不存在或已禁用", HttpStatus.NOT_FOUND),
                Arguments.of("重复提交同一文件资料", ErrorCode.DATA_DUPLICATE, "已提交过相同文件的待审核或已通过资料", HttpStatus.BAD_REQUEST)
        );
    }

    private RequestPostProcessor bearerToken() {
        return request -> {
            request.addHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + TEST_TOKEN);
            return request;
        };
    }

    private String validCreateBody() {
        return """
                {
                  "fileId": 200,
                  "title": "Java 期末复习课件",
                  "description": "覆盖集合、IO 和并发基础",
                  "categoryId": 10,
                  "courseName": "Java 程序设计",
                  "resourceType": 1,
                  "tags": ["Java", "复习"]
                }
                """;
    }

    private ResourceDetailVO buildResourceDetailVO() {
        return new ResourceDetailVO(
                100L,
                "Java 期末复习课件",
                "覆盖集合、IO 和并发基础",
                10L,
                "计算机基础",
                "Java 程序设计",
                1,
                List.of("Java", "复习"),
                1,
                0L,
                0L,
                BigDecimal.ZERO,
                LocalDateTime.now(),
                null
        );
    }
}
