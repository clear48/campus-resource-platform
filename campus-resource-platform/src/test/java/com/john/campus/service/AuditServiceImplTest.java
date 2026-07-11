package com.john.campus.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.AuditApproveDTO;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.entity.Resource;
import com.john.campus.mapper.AuditRecordMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.AuditServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * 审核热度联动测试：排行榜更新必须等 MySQL 事务提交后执行，防止回滚时留下可见的错误榜单成员。
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private AuditRecordMapper auditRecordMapper;
    @Mock
    private RankingService rankingService;

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditServiceImpl(resourceMapper, auditRecordMapper, rankingService);
        UserContextHolder.set(new LoginUser(90001L, 2, "audit-heat-test-jti"));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void approveShouldInitializeRankingOnlyAfterCommit() {
        Resource pendingResource = new Resource();
        pendingResource.setId(100L);
        pendingResource.setStatus(Resource.STATUS_PENDING_REVIEW);
        when(resourceMapper.selectById(100L)).thenReturn(pendingResource);
        when(resourceMapper.approvePendingReview(org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        auditService.approve(100L, new AuditApproveDTO());

        org.mockito.Mockito.verifyNoInteractions(rankingService);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(rankingService).initializeApprovedResource(100L);
    }

    @Test
    void offlineShouldRemoveRankingOnlyAfterCommit() {
        Resource approvedResource = new Resource();
        approvedResource.setId(100L);
        approvedResource.setStatus(Resource.STATUS_APPROVED);
        when(resourceMapper.selectById(100L)).thenReturn(approvedResource);
        when(resourceMapper.offlineApprovedResource(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        ResourceOfflineDTO dto = new ResourceOfflineDTO();
        dto.setOfflineReason("版权风险");
        TransactionSynchronizationManager.initSynchronization();

        auditService.offline(100L, dto);

        org.mockito.Mockito.verifyNoInteractions(rankingService);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(rankingService).removeOfflineResource(100L);
    }
}
