package com.john.campus.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.JwtUtils;
import com.john.campus.config.WebMvcConfig;
import com.john.campus.dto.HotResourceRankingQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.exception.GlobalExceptionHandler;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.RankingService;
import com.john.campus.vo.HotResourceRankingVO;
import com.john.campus.vo.HotSearchKeywordRankingVO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 排行榜 Controller 测试：验证公开访问、查询参数绑定和 Bean Validation 在进入 Service 前生效。
 */
@WebMvcTest(RankingController.class)
@Import({WebMvcConfig.class, JwtAuthenticationInterceptor.class, GlobalExceptionHandler.class})
class RankingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RankingService rankingService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void hotResourcesShouldBindQueryAndAllowAnonymousAccess() throws Exception {
        when(rankingService.listHotResources(any(HotResourceRankingQueryDTO.class)))
                .thenReturn(List.of(new HotResourceRankingVO(
                        1, 100L, "Java 期末复习课件", "Java 程序设计", 20L, 5L, new BigDecimal("25.0"))));

        mockMvc.perform(get("/api/v1/rankings/resources/hot")
                        .param("limit", "2")
                        .param("categoryId", "10")
                        .param("period", "weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].resourceId").value(100))
                .andExpect(jsonPath("$.data[0].rank").value(1));

        ArgumentCaptor<HotResourceRankingQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(HotResourceRankingQueryDTO.class);
        verify(rankingService).listHotResources(queryCaptor.capture());
        HotResourceRankingQueryDTO query = queryCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(2, query.getLimit());
        org.junit.jupiter.api.Assertions.assertEquals(10L, query.getCategoryId());
        org.junit.jupiter.api.Assertions.assertEquals("weekly", query.getPeriod());
        // WebMvcConfig 已显式放行排行榜路径，公开查询不应尝试解析 JWT。
        verify(jwtUtils, never()).parseToken(anyString());
    }

    @Test
    void hotSearchKeywordsShouldBindQueryAndAllowAnonymousAccess() throws Exception {
        when(rankingService.listHotSearchKeywords(any(HotSearchKeywordRankingQueryDTO.class)))
                .thenReturn(List.of(new HotSearchKeywordRankingVO(1, "spring boot", 12L)));

        mockMvc.perform(get("/api/v1/rankings/search-keywords/hot")
                        .param("limit", "3")
                        .param("period", "daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].keyword").value("spring boot"))
                .andExpect(jsonPath("$.data[0].searchCount").value(12));

        ArgumentCaptor<HotSearchKeywordRankingQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(HotSearchKeywordRankingQueryDTO.class);
        verify(rankingService).listHotSearchKeywords(queryCaptor.capture());
        HotSearchKeywordRankingQueryDTO query = queryCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(3, query.getLimit());
        org.junit.jupiter.api.Assertions.assertEquals("daily", query.getPeriod());
        verify(jwtUtils, never()).parseToken(anyString());
    }

    @Test
    void hotResourcesShouldRejectInvalidQueryBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/resources/hot")
                        .param("limit", "51")
                        .param("categoryId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        // 参数越界时由 Controller 层校验提前终止，不能将无效查询交给缓存或数据库层。
        verify(rankingService, never()).listHotResources(any(HotResourceRankingQueryDTO.class));
    }

    @Test
    void hotSearchKeywordsShouldRejectInvalidLimitBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/rankings/search-keywords/hot")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));

        verify(rankingService, never()).listHotSearchKeywords(any(HotSearchKeywordRankingQueryDTO.class));
    }
}
