package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.exception.BusinessException;
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

    @Test
    void shouldPersistEveryDeltaWithAtomicMapperUpdate() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(resourceMapper);
        Map<Long, Long> deltas = new LinkedHashMap<>();
        deltas.put(101L, 5L);
        deltas.put(102L, 3L);
        when(resourceMapper.incrementDownloadCount(101L, 5L)).thenReturn(1);
        when(resourceMapper.incrementDownloadCount(102L, 3L)).thenReturn(1);

        persistenceService.persistDownloadDeltas(deltas);

        verify(resourceMapper).incrementDownloadCount(101L, 5L);
        verify(resourceMapper).incrementDownloadCount(102L, 3L);
    }

    @Test
    void shouldThrowWhenAnyResourceCannotBeUpdated() {
        DownloadDeltaPersistenceService persistenceService = new DownloadDeltaPersistenceServiceImpl(resourceMapper);
        when(resourceMapper.incrementDownloadCount(101L, 5L)).thenReturn(0);

        assertThatThrownBy(() -> persistenceService.persistDownloadDeltas(Map.of(101L, 5L)))
                .isInstanceOf(BusinessException.class);
    }
}
