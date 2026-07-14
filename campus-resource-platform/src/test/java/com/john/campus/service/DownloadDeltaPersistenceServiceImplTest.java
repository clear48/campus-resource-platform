package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.DownloadDeltaSyncItemMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.DownloadDeltaPersistenceServiceImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 下载增量持久化测试：验证每个资料使用原子 SQL 累加，更新异常必须向外传播以便保留 Redis syncing 批次。
 */
@ExtendWith(MockitoExtension.class)
class DownloadDeltaPersistenceServiceImplTest {

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private DownloadDeltaSyncItemMapper downloadDeltaSyncItemMapper;

    private static final String BATCH_ID = "5f0e4d2e-7d33-4ab3-ae06-9c2afcc9d201";

    @Test
    void shouldPersistEveryDeltaWithAtomicMapperUpdate() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(
                resourceMapper, downloadDeltaSyncItemMapper);
        Map<Long, Long> deltas = new LinkedHashMap<>();
        deltas.put(101L, 5L);
        deltas.put(102L, 3L);
        when(downloadDeltaSyncItemMapper.insertIgnore(BATCH_ID, 101L, 5L)).thenReturn(1);
        when(downloadDeltaSyncItemMapper.insertIgnore(BATCH_ID, 102L, 3L)).thenReturn(1);
        when(resourceMapper.incrementDownloadCount(101L, 5L)).thenReturn(1);
        when(resourceMapper.incrementDownloadCount(102L, 3L)).thenReturn(1);

        persistenceService.persistDownloadDeltas(BATCH_ID, deltas);

        verify(downloadDeltaSyncItemMapper).insertIgnore(BATCH_ID, 101L, 5L);
        verify(downloadDeltaSyncItemMapper).insertIgnore(BATCH_ID, 102L, 3L);
        verify(resourceMapper).incrementDownloadCount(101L, 5L);
        verify(resourceMapper).incrementDownloadCount(102L, 3L);
    }

    @Test
    void shouldSkipAtomicUpdateWhenSameBatchDeltaWasAlreadyPersisted() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(
                resourceMapper, downloadDeltaSyncItemMapper);
        when(downloadDeltaSyncItemMapper.insertIgnore(BATCH_ID, 101L, 5L)).thenReturn(0);
        when(downloadDeltaSyncItemMapper.selectDelta(BATCH_ID, 101L)).thenReturn(5L);

        persistenceService.persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));

        // MySQL 已提交、Redis HDEL 失败后重试时，唯一键命中只能确认，不允许再次累加下载量。
        verify(resourceMapper, never()).incrementDownloadCount(101L, 5L);
    }

    @Test
    void shouldRejectMismatchedDeltaForSameBatchAndResource() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(
                resourceMapper, downloadDeltaSyncItemMapper);
        when(downloadDeltaSyncItemMapper.insertIgnore(BATCH_ID, 101L, 5L)).thenReturn(0);
        when(downloadDeltaSyncItemMapper.selectDelta(BATCH_ID, 101L)).thenReturn(3L);

        assertThatThrownBy(() -> persistenceService.persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L)))
                .isInstanceOf(BusinessException.class);
        verify(resourceMapper, never()).incrementDownloadCount(101L, 5L);
    }

    @Test
    void shouldThrowWhenAnyResourceCannotBeUpdated() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(
                resourceMapper, downloadDeltaSyncItemMapper);
        when(downloadDeltaSyncItemMapper.insertIgnore(BATCH_ID, 101L, 5L)).thenReturn(1);
        when(resourceMapper.incrementDownloadCount(101L, 5L)).thenReturn(0);

        assertThatThrownBy(() -> persistenceService.persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L)))
                .isInstanceOf(BusinessException.class);
    }
}
