package com.john.campus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 热门搜索词排行榜查询参数，周期范围由后续 Service 限制为不包含 all 的搜索词周期。
 */
@Getter
@Setter
public class HotSearchKeywordRankingQueryDTO {

    /**
     * 返回榜单数量，限制在 1 到 50 条之间，避免一次读取过多 ZSet 成员。
     */
    @Min(value = 1, message = "limit 必须大于等于 1")
    @Max(value = 50, message = "limit 不能大于 50")
    private Integer limit;

    /**
     * 热门搜索词周期原始值，Service 将使用 RankingPeriod 拒绝 all 周期。
     */
    private String period;
}
