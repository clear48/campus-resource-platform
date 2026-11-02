package com.john.campus.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.FileInfo;
import com.john.campus.mapper.FileBackupRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.service.impl.FileBackupServiceImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** 验证副本任务的游标扫描、每文件锁、READY落库和Redis故障关闭。 */
@ExtendWith(MockitoExtension.class)
class FileBackupServiceImplTest {

    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private FileBackupRecordMapper backupRecordMapper;
    @Mock
    private FileProtectionStorageService storageService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private FileBackupServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileBackupServiceImpl(
                fileInfoMapper, backupRecordMapper, storageService, redissonClient, 100);
    }

    @Test
    void shouldCreateVerifiedBackupUnderPerFileLock() {
        FileInfo fileInfo = normalFile(7L);
        when(fileInfoMapper.selectWithoutReadyBackupAfterId(0L, 100)).thenReturn(List.of(fileInfo));
        when(redissonClient.getLock(RedisKeyConstants.fileProtectionLock(7L))).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(fileInfoMapper.selectNormalById(7L)).thenReturn(fileInfo);
        when(storageService.createVerifiedBackup(fileInfo)).thenReturn(
                new FileProtectionStorageService.BackupArtifact("D:/backup/hash", 12L,
                        "0123456789abcdef0123456789abcdef", "a".repeat(64)));

        service.backupPendingFiles();

        verify(backupRecordMapper).upsertPending(fileInfo);
        verify(backupRecordMapper).markReady(
                eq(7L), eq("D:/backup/hash"), eq(12L), eq("0123456789abcdef0123456789abcdef"),
                eq("a".repeat(64)));
        verify(lock).unlock();
    }

    @Test
    void shouldFailClosedWhenRedisLockCannotBeObtained() {
        FileInfo fileInfo = normalFile(8L);
        when(fileInfoMapper.selectWithoutReadyBackupAfterId(0L, 100)).thenReturn(List.of(fileInfo));
        when(redissonClient.getLock(RedisKeyConstants.fileProtectionLock(8L)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        service.backupPendingFiles();

        verify(storageService, never()).createVerifiedBackup(any());
        verify(backupRecordMapper, never()).markReady(anyLong(), any(), anyLong(), any(), any());
    }

    private FileInfo normalFile(long id) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setFileMd5("0123456789abcdef0123456789abcdef");
        fileInfo.setFileSize(12L);
        fileInfo.setStorageType(FileInfo.STORAGE_TYPE_LOCAL);
        fileInfo.setStoragePath("C:/uploads/file.txt");
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }
}
