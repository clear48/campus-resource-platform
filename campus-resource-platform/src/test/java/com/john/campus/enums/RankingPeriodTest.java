package com.john.campus.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.common.RedisKeyConstants;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 排行榜周期和 Key 单元测试，确保后续业务层使用统一白名单与 Redis 命名。
 */
class RankingPeriodTest {

    @Test
    void periodTtlShouldMatchRedisDesign() {
        assertThat(RankingPeriod.DAILY.getTtl()).contains(Duration.ofDays(2));
        assertThat(RankingPeriod.WEEKLY.getTtl()).contains(Duration.ofDays(14));
        assertThat(RankingPeriod.MONTHLY.getTtl()).contains(Duration.ofDays(60));
        assertThat(RankingPeriod.ALL.getTtl()).isEmpty();
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
        assertThat(RedisKeyConstants.resourceHotRank(RankingPeriod.DAILY.getCode()))
                .isEqualTo("crp:rank:resource:hot:daily");
        assertThat(RedisKeyConstants.downloadDeltaSyncing("batch-001"))
                .isEqualTo("crp:stats:resource:download:syncing:batch-001");
        assertThat(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK)
                .isEqualTo("crp:lock:sync:download-delta");
    }
}
