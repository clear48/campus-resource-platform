package com.john.campus.enums;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 排行榜统计周期，集中维护 Redis Key 后缀、保留时间和各榜单支持范围。
 */
public enum RankingPeriod {

    /**
     * 日榜在自然日结束后额外保留 2 天。
     */
    DAILY("daily", Duration.ofDays(2), true),

    /**
     * 周榜在自然周结束后额外保留 14 天。
     */
    WEEKLY("weekly", Duration.ofDays(14), true),

    /**
     * 月榜在自然月结束后额外保留 60 天。
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
    private final Duration retention;

    /**
     * 标记该周期是否允许用于热门搜索词排行榜。
     */
    private final boolean searchKeywordRankingSupported;

    RankingPeriod(String code, Duration retention, boolean searchKeywordRankingSupported) {
        this.code = code;
        this.retention = retention;
        this.searchKeywordRankingSupported = searchKeywordRankingSupported;
    }

    public String getCode() {
        return code;
    }

    /**
     * 返回周期结束后的保留时长；该时长不是统计窗口。
     */
    public Optional<Duration> getRetention() {
        return Optional.ofNullable(retention);
    }

    /** 业务时间统一使用上海时区，不随服务器或测试 Clock 的时区变化。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 按事件时间定位自然周期；周桶直接使用周一日期，避免跨年周编号歧义。 */
    public String keySuffix(Instant eventTime) {
        LocalDate date = Objects.requireNonNull(eventTime, "事件时间不能为空").atZone(BUSINESS_ZONE).toLocalDate();
        return switch (this) {
            case DAILY -> code + ":" + date;
            case WEEKLY -> code + ":" + date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> code + ":" + YearMonth.from(date);
            case ALL -> code;
        };
    }

    /** 固定在周期结束加保留期时回收，同一桶重复写入不会延长存活时间。 */
    public Optional<Instant> expiresAt(Instant eventTime) {
        LocalDate date = Objects.requireNonNull(eventTime, "事件时间不能为空").atZone(BUSINESS_ZONE).toLocalDate();
        if (this == ALL) {
            return Optional.empty();
        }
        LocalDate end = switch (this) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1);
            case MONTHLY -> date.withDayOfMonth(1).plusMonths(1);
            case ALL -> throw new IllegalStateException("总榜无周期结束时间");
        };
        return Optional.of(end.atStartOfDay(BUSINESS_ZONE).toInstant().plus(retention));
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
