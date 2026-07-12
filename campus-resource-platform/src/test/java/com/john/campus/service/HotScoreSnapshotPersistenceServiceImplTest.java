package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.HotScoreSnapshotPersistenceServiceImpl;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证热度快照事务服务逐条调用受 APPROVED 状态保护的 Mapper SQL，并向外传播数据库异常以触发回滚。 */
@ExtendWith(MockitoExtension.class)
class HotScoreSnapshotPersistenceServiceImplTest {

    @Mock
    private ResourceMapper resourceMapper;

    @Test
    void shouldPersistEveryHotScoreThroughApprovedOnlyMapperUpdate() {
        HotScoreSnapshotPersistenceService service = new HotScoreSnapshotPersistenceServiceImpl(resourceMapper);
        Map<Long, BigDecimal> hotScores = Map.of(101L, BigDecimal.valueOf(8D), 102L, BigDecimal.valueOf(15D));

        service.persistApprovedHotScores(hotScores);

        verify(resourceMapper).updateApprovedHotScore(101L, BigDecimal.valueOf(8D));
        verify(resourceMapper).updateApprovedHotScore(102L, BigDecimal.valueOf(15D));
    }

    @Test
    void shouldPropagateMapperFailureSoTransactionCanRollback() {
        HotScoreSnapshotPersistenceService service = new HotScoreSnapshotPersistenceServiceImpl(resourceMapper);
        when(resourceMapper.updateApprovedHotScore(101L, BigDecimal.ONE))
                .thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> service.persistApprovedHotScores(Map.of(101L, BigDecimal.ONE)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("database unavailable");
    }
}
