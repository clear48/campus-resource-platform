package com.john.campus.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 排行榜查询 DTO 单元测试，验证 HTTP 参数边界，周期白名单则由后续 Service 负责。
 */
class RankingQueryDTOTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void hotResourceQueryShouldAllowOptionalParameters() {
        HotResourceRankingQueryDTO query = new HotResourceRankingQueryDTO();

        assertThat(validateMessages(query)).isEmpty();
    }

    @Test
    void hotResourceQueryShouldValidateLimitAndCategoryId() {
        HotResourceRankingQueryDTO query = new HotResourceRankingQueryDTO();
        query.setLimit(51);
        query.setCategoryId(0L);

        assertThat(validateMessages(query))
                .containsExactlyInAnyOrder("limit 不能大于 50", "分类 ID 必须大于 0");
    }

    @Test
    void hotSearchKeywordQueryShouldValidateLimitOnly() {
        HotSearchKeywordRankingQueryDTO query = new HotSearchKeywordRankingQueryDTO();
        query.setLimit(0);
        query.setPeriod("all");

        // all 的业务周期限制由 RankingService 基于 RankingPeriod 处理，DTO 仅校验基础参数。
        assertThat(validateMessages(query)).containsExactly("limit 必须大于等于 1");
    }

    /**
     * 提取错误消息，避免断言与校验器实现细节或属性路径耦合。
     */
    private Set<String> validateMessages(Object target) {
        Set<ConstraintViolation<Object>> violations = validator.validate(target);
        return violations.stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
    }
}
