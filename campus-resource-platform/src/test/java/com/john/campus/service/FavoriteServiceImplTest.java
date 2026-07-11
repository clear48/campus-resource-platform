package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.Favorite;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.FavoriteMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.FavoriteServiceImpl;
import com.john.campus.vo.FavoriteResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 收藏热度联动测试：只验证 MySQL 状态已成功变更后才通知排行榜，重复请求不得重复计分。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock
    private FavoriteMapper favoriteMapper;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RankingService rankingService;

    private FavoriteService favoriteService;

    @BeforeEach
    void setUp() {
        favoriteService = new FavoriteServiceImpl(
                favoriteMapper, resourceMapper, stringRedisTemplateProvider, transactionTemplate, rankingService);
        UserContextHolder.set(new LoginUser(10001L, 1, "favorite-heat-test-jti"));
        lenient().when(stringRedisTemplateProvider.getIfAvailable()).thenReturn(null);
        lenient().when(resourceMapper.selectById(100L)).thenReturn(approvedResource());
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<FavoriteResultVO> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    @Test
    void favoriteShouldRecordHeatOnlyWhenNewFavoriteIsCommitted() {
        when(favoriteMapper.selectByUserAndResource(10001L, 100L)).thenReturn(null);
        when(resourceMapper.updateFavoriteCount(100L, 1)).thenReturn(1);

        FavoriteResultVO result = favoriteService.favorite(100L);

        assertThat(result.duplicateIgnored()).isFalse();
        verify(rankingService).recordResourceFavorite(100L);
    }

    @Test
    void repeatedFavoriteShouldNotRecordHeatAgain() {
        Favorite existing = new Favorite();
        existing.setStatus(Favorite.STATUS_FAVORITED);
        when(favoriteMapper.selectByUserAndResource(10001L, 100L)).thenReturn(existing);

        FavoriteResultVO result = favoriteService.favorite(100L);

        assertThat(result.duplicateIgnored()).isTrue();
        verify(rankingService, never()).recordResourceFavorite(100L);
    }

    @Test
    void unfavoriteShouldRecordHeatOnlyAfterStatusTransitionSucceeds() {
        Favorite existing = new Favorite();
        existing.setId(10L);
        existing.setStatus(Favorite.STATUS_FAVORITED);
        when(favoriteMapper.selectByUserAndResource(10001L, 100L)).thenReturn(existing);
        when(favoriteMapper.updateStatus(10L, Favorite.STATUS_FAVORITED, Favorite.STATUS_CANCELED)).thenReturn(1);
        when(resourceMapper.updateFavoriteCount(100L, -1)).thenReturn(1);

        FavoriteResultVO result = favoriteService.unfavorite(100L);

        assertThat(result.favorited()).isFalse();
        verify(rankingService).recordResourceUnfavorite(100L);
    }

    @Test
    void repeatedUnfavoriteShouldNotRecordHeat() {
        Favorite existing = new Favorite();
        existing.setStatus(Favorite.STATUS_CANCELED);
        when(favoriteMapper.selectByUserAndResource(10001L, 100L)).thenReturn(existing);

        assertThatThrownBy(() -> favoriteService.unfavorite(100L))
                .isInstanceOf(BusinessException.class);

        // 沿用既有“取消不存在收藏返回不存在”的接口语义，同时确保不会重复扣减热度。
        verify(rankingService, never()).recordResourceUnfavorite(100L);
    }

    @Test
    void rankingFailureShouldNotFailCommittedFavorite() {
        when(favoriteMapper.selectByUserAndResource(10001L, 100L)).thenReturn(null);
        when(resourceMapper.updateFavoriteCount(100L, 1)).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("redis unavailable"))
                .when(rankingService).recordResourceFavorite(100L);

        FavoriteResultVO result = favoriteService.favorite(100L);

        assertThat(result.favorited()).isTrue();
        verify(favoriteMapper).insert(any());
    }

    private Resource approvedResource() {
        Resource resource = new Resource();
        resource.setId(100L);
        resource.setStatus(Resource.STATUS_APPROVED);
        resource.setFavoriteCount(1L);
        return resource;
    }
}
