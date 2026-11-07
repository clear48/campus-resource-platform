package com.john.campus.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.common.RedisKeyConstants;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * 排行榜周期和 Key 单元测试，确保后续业务层使用统一白名单与 Redis 命名。
 */
class RankingPeriodTest {

    @Test
    void periodTtlShouldMatchRedisDesign() {
        assertThat(RankingPeriod.DAILY.getRetention()).contains(Duration.ofDays(2));
        assertThat(RankingPeriod.WEEKLY.getRetention()).contains(Duration.ofDays(14));
        assertThat(RankingPeriod.MONTHLY.getRetention()).contains(Duration.ofDays(60));
        assertThat(RankingPeriod.ALL.getRetention()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-11-08T15:59:59Z,daily:2026-11-08,weekly:2026-11-02,monthly:2026-11",
        "2026-11-08T16:00:00Z,daily:2026-11-09,weekly:2026-11-09,monthly:2026-11",
        "2026-11-30T16:00:00Z,daily:2026-12-01,weekly:2026-11-30,monthly:2026-12",
        "2027-01-01T00:00:00Z,daily:2027-01-01,weekly:2026-12-28,monthly:2027-01",
        "2024-02-29T15:59:59Z,daily:2024-02-29,weekly:2024-02-26,monthly:2024-02",
        "2024-02-29T16:00:00Z,daily:2024-03-01,weekly:2024-02-26,monthly:2024-03"
    })
    void keysShouldFollowShanghaiCalendar(String time, String day, String week, String month) {
        Instant instant = Instant.parse(time);
        assertThat(RankingPeriod.DAILY.keySuffix(instant)).isEqualTo(day);
        assertThat(RankingPeriod.WEEKLY.keySuffix(instant)).isEqualTo(week);
        assertThat(RankingPeriod.MONTHLY.keySuffix(instant)).isEqualTo(month);
    }

    @Test
    void expiryShouldBeFixedAtPeriodEndPlusRetention() {
        Instant morning = Instant.parse("2026-11-07T01:00:00Z");
        Instant evening = Instant.parse("2026-11-07T15:59:59Z");
        assertThat(RankingPeriod.DAILY.expiresAt(morning)).contains(Instant.parse("2026-11-09T16:00:00Z"));
        assertThat(RankingPeriod.WEEKLY.expiresAt(morning)).contains(Instant.parse("2026-11-22T16:00:00Z"));
        assertThat(RankingPeriod.MONTHLY.expiresAt(morning)).contains(Instant.parse("2027-01-29T16:00:00Z"));
        for (RankingPeriod period : RankingPeriod.values()) {
            assertThat(period.expiresAt(evening)).isEqualTo(period.expiresAt(morning));
        }
        assertThat(RankingPeriod.ALL.expiresAt(morning)).isEmpty();
        assertThat(RankingPeriod.ALL.keySuffix(morning)).isEqualTo("all");
    }

    @Test
    void searchKeywordRankingShouldRejectAllPeriod() {
        assertThat(RankingPeriod.DAILY.isSearchKeywordRankingSupported()).isTrue();
        assertThat(RankingPeriod.WEEKLY.isSearchKeywordRankingSupported()).isTrue();
        assertThat(RankingPeriod.MONTHLY.isSearchKeywordRankingSupported()).isTrue();
        assertThat(RankingPeriod.ALL.isSearchKeywordRankingSupported()).isFalse();
    }

    @Test
    void fromCodeShouldNormalizeCaseAndWhitespace() {
        assertThat(RankingPeriod.fromCode(" Weekly ")).contains(RankingPeriod.WEEKLY);
        assertThat(RankingPeriod.fromCode("unknown")).isEmpty();
        assertThat(RankingPeriod.fromCode(null)).isEmpty();
    }

    @Test
    void rankingKeysShouldUseCanonicalFormats() {
        assertThat(RedisKeyConstants.resourceHotRank(RankingPeriod.DAILY, Instant.parse("2026-11-07T12:00:00Z")))
                .isEqualTo("crp:rank:resource:hot:daily:2026-11-07");
        assertThat(RedisKeyConstants.downloadDeltaSyncing("batch-001"))
                .isEqualTo("crp:stats:resource:download:syncing:batch-001");
        assertThat(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK)
                .isEqualTo("crp:lock:sync:download-delta");
    }
}
