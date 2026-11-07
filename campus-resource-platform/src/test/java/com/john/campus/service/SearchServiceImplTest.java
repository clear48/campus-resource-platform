package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.john.campus.dto.SearchResourceQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.SearchServiceImpl;
import com.john.campus.service.impl.RankingServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/** 用共享内存分数验证真实写后读与自然周期隔离，旧桶保留以防止假阳性。 */
class SearchServiceImplTest {
    @Test
    @SuppressWarnings("unchecked")
    void searchAndRankingShouldKeepOldScoresOutsideNewPeriods() {
        ResourceMapper mapper = mock(ResourceMapper.class);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForZSet()).thenReturn(zset);
        Clock clock = mock(Clock.class);
        Map<String, Map<String, Double>> scores = new HashMap<>();
        Map<String, Date> expiry = new HashMap<>();
        when(zset.incrementScore(anyString(), anyString(), anyDouble())).thenAnswer(call ->
                scores.computeIfAbsent(call.getArgument(0), key -> new HashMap<>())
                        .merge(call.getArgument(1), call.getArgument(2), Double::sum));
        when(redis.expireAt(anyString(), any(Date.class))).thenAnswer(call -> {
            expiry.put(call.getArgument(0), call.getArgument(1));
            return true;
        });
        when(zset.reverseRangeWithScores(anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            Set<ZSetOperations.TypedTuple<String>> result = new LinkedHashSet<>();
            scores.getOrDefault(call.getArgument(0), Map.of()).forEach((word, count) -> result.add(new DefaultTypedTuple<>(word, count)));
            return result;
        });
        SearchService search = new SearchServiceImpl(mapper, provider, clock);
        RankingService ranking = new RankingServiceImpl(mapper, provider, null, clock);
        SearchResourceQueryDTO query = new SearchResourceQueryDTO();
        query.setKeyword(" Java ");
        when(clock.instant()).thenReturn(Instant.parse("2026-02-01T15:59:59Z"));
        search.searchResources(query);
        search.searchResources(query);
        assertThat(scores.get("crp:rank:search:keyword:daily:2026-02-01")).containsEntry("Java", 2D);
        assertThat(expiry.get("crp:rank:search:keyword:daily:2026-02-01"))
                .isEqualTo(Date.from(Instant.parse("2026-02-03T16:00:00Z")));
        // 周日跨周一：读取新日桶、新周桶时不能看到仍存在的旧桶分数。
        when(clock.instant()).thenReturn(Instant.parse("2026-02-01T16:00:00Z"));
        for (String period : List.of("daily", "weekly")) {
            HotSearchKeywordRankingQueryDTO read = new HotSearchKeywordRankingQueryDTO();
            read.setPeriod(period);
            assertThat(ranking.listHotSearchKeywords(read)).isEmpty();
        }
        search.searchResources(query);
        assertThat(scores.get("crp:rank:search:keyword:weekly:2026-02-02")).containsEntry("Java", 1D);
        when(clock.instant()).thenReturn(Instant.parse("2026-02-28T16:00:00Z"));
        HotSearchKeywordRankingQueryDTO read = new HotSearchKeywordRankingQueryDTO();
        read.setPeriod("monthly");
        assertThat(ranking.listHotSearchKeywords(read)).isEmpty();
        search.searchResources(query);
        assertThat(ranking.listHotSearchKeywords(read).get(0).searchCount()).isEqualTo(1L);
        assertThat(scores.get("crp:rank:search:keyword:monthly:2026-02")).containsEntry("Java", 3D);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordRecordingShouldSampleOnceAndTolerateRedisFailure() {
        ResourceMapper mapper = mock(ResourceMapper.class);
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForZSet()).thenReturn(zset);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-08T15:59:59Z"), Instant.parse("2026-11-08T16:00:01Z"));
        SearchService service = new SearchServiceImpl(mapper, provider, clock);
        SearchResourceQueryDTO query = new SearchResourceQueryDTO();
        query.setKeyword("Java");
        assertThat(service.searchResources(query).total()).isZero();
        verify(clock).instant();
        verify(zset).incrementScore("crp:rank:search:keyword:daily:2026-11-08", "Java", 1D);
        verify(zset).incrementScore("crp:rank:search:keyword:weekly:2026-11-02", "Java", 1D);
        when(zset.incrementScore(anyString(), anyString(), anyDouble())).thenThrow(new RuntimeException("redis unavailable"));
        assertThat(service.searchResources(query).total()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankKeywordShouldSkipRedisAndClock() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        Clock clock = mock(Clock.class);
        SearchService service = new SearchServiceImpl(mock(ResourceMapper.class), provider, clock);
        SearchResourceQueryDTO query = new SearchResourceQueryDTO();
        query.setKeyword("   ");
        assertThat(service.searchResources(query).records()).isEmpty();
        verifyNoInteractions(provider, clock);
    }
}
