package com.john.campus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 热门资料排行榜查询参数，只承载 HTTP 请求中的筛选条件。
 * 默认数量和周期白名单由后续 RankingService 统一处理，避免 DTO 与业务策略耦合。
 */
@Getter
@Setter
public class HotResourceRankingQueryDTO {

    /**
     * 返回榜单数量，接口最大只允许 50 条，保护 Redis 查询和响应体大小。
     */
    @Min(value = 1, message = "limit 必须大于等于 1")
    @Max(value = 50, message = "limit 不能大于 50")
    private Integer limit;

    /**
     * 可选分类筛选条件；非空时必须为正数，避免无意义的资料查询。
     */
    @Positive(message = "分类 ID 必须大于 0")
    private Long categoryId;

    /**
     * 排行榜周期原始值，Service 将通过 RankingPeriod 解析 daily、weekly、monthly、all 白名单。
     */
    private String period;
}
