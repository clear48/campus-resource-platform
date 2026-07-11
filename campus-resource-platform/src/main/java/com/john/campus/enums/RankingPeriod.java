package com.john.campus.enums;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 排行榜统计周期，集中维护 Redis Key 后缀、保留时间和各榜单支持范围。
 */
public enum RankingPeriod {

    /**
     * 日榜保留 2 天，便于跨日展示和问题排查。
     */
    DAILY("daily", Duration.ofDays(2), true),

    /**
     * 周榜保留 14 天，覆盖当前周期并保留一周缓冲。
     */
    WEEKLY("weekly", Duration.ofDays(14), true),

    /**
     * 月榜保留 60 天，覆盖当前周期并保留一个月缓冲。
     */
    MONTHLY("monthly", Duration.ofDays(60), true),

    /**
     * 总榜不设置 TTL；热门搜索词当前没有总榜，避免无限积累运营数据。
     */
    ALL("all", null, false);

    /**
     * Redis Key 使用的稳定周期编码。
     */
    private final String code;

    /**
     * 周期榜的 Redis 保留时间；总榜为 null，表示业务代码不得设置过期时间。
     */
    private final Duration ttl;

    /**
     * 标记该周期是否允许用于热门搜索词排行榜。
     */
    private final boolean searchKeywordRankingSupported;

    RankingPeriod(String code, Duration ttl, boolean searchKeywordRankingSupported) {
        this.code = code;
        this.ttl = ttl;
        this.searchKeywordRankingSupported = searchKeywordRankingSupported;
    }

    public String getCode() {
        return code;
    }

    /**
     * 返回可选 TTL，强制调用方显式处理总榜不应过期的业务规则。
     */
    public Optional<Duration> getTtl() {
        return Optional.ofNullable(ttl);
    }

    public boolean isSearchKeywordRankingSupported() {
        return searchKeywordRankingSupported;
    }

    /**
     * 按外部周期编码查找枚举；统一去除首尾空格并忽略大小写。
     */
    public static Optional<RankingPeriod> fromCode(String code) {
        if (code == null) {
            // 传入 null 时返回空 Optional，避免抛出 NullPointerException。
            return Optional.empty();
        }
        String normalizedCode = code.trim().toLowerCase(Locale.ROOT);
        /*
        values() 是 Java 编译器自动为每个枚举类生成的方法。
        它会返回当前枚举的所有枚举对象组成的数组。
         */
        return Arrays.stream(values())
                .filter(period -> period.code.equals(normalizedCode))
                .findFirst();
    }
}
