package com.john.campus.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.FileBackupRecord;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.FileIntegrityEvent;
import com.john.campus.mapper.FileBackupRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.FileIntegrityEventMapper;
import com.john.campus.service.impl.FileIntegrityServiceImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** 验证缺失二次确认、单文件原子恢复编排和批量缺失熔断。 */
@ExtendWith(MockitoExtension.class)
class FileIntegrityServiceImplTest {

    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private FileBackupRecordMapper backupRecordMapper;
    @Mock
    private FileIntegrityEventMapper eventMapper;
    @Mock
    private FileProtectionStorageService storageService;
    @Mock
    private FileMd5CacheService md5CacheService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private FileIntegrityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileIntegrityServiceImpl(
                fileInfoMapper, backupRecordMapper, eventMapper, storageService, md5CacheService,
                redissonClient, 100, 2, 0.01, 3);
    }

    @Test
    void shouldOnlyRecordFirstMissingDetection() {
        FileInfo fileInfo = normalFile(1L);
        prepareSingleMissing(fileInfo);
        when(eventMapper.selectLatestOpenMissing(1L)).thenReturn(null);

        service.scanAndRecover();

        ArgumentCaptor<FileIntegrityEvent> eventCaptor = ArgumentCaptor.forClass(FileIntegrityEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        verify(storageService, never()).restoreMissing(any(), any());
        org.assertj.core.api.Assertions.assertThat(eventCaptor.getValue().getStatus())
                .isEqualTo(FileIntegrityEvent.STATUS_DETECTED);
    }

    @Test
    void shouldRestoreOnSecondConfirmedDetection() {
        FileInfo fileInfo = normalFile(2L);
        prepareSingleMissing(fileInfo);
        FileIntegrityEvent event = detectedEvent(22L, 2L);
        FileBackupRecord backup = readyBackup(32L, 2L);
        when(eventMapper.selectLatestOpenMissing(2L)).thenReturn(event);
        when(backupRecordMapper.selectByFileId(2L)).thenReturn(backup);
        when(eventMapper.markConfirmedAndRestoring(22L, 32L)).thenReturn(1);
        when(storageService.restoreMissing(fileInfo, backup)).thenReturn(true);

        service.scanAndRecover();

        verify(storageService).restoreMissing(fileInfo, backup);
        verify(eventMapper).markRecovered(22L);
        verify(md5CacheService).evict(fileInfo.getFileMd5(), fileInfo.getFileSize());
    }

    @Test
    void shouldFuseWhenMoreThanOneFileIsMissing() {
        FileInfo first = normalFile(3L);
        FileInfo second = normalFile(4L);
        when(fileInfoMapper.selectNormalLocalAfterId(0L, 100)).thenReturn(List.of(first, second));
        when(storageService.inspect(any())).thenReturn(FileProtectionStorageService.FileState.MISSING);

        service.scanAndRecover();

        verify(redissonClient, never()).getLock(anyString());
        verify(storageService, never()).restoreMissing(any(), any());
        verify(eventMapper, org.mockito.Mockito.times(2)).insert(any(FileIntegrityEvent.class));
    }

    @Test
    void shouldClosePreviousDetectionWhenFileBecomesHealthy() {
        FileInfo fileInfo = normalFile(5L);
        FileIntegrityEvent open = detectedEvent(55L, 5L);
        when(eventMapper.selectOpenMissingFileIds()).thenReturn(List.of(5L));
        when(fileInfoMapper.selectNormalLocalAfterId(0L, 100)).thenReturn(List.of(fileInfo));
        when(storageService.inspect(fileInfo)).thenReturn(FileProtectionStorageService.FileState.HEALTHY);
        when(eventMapper.selectLatestOpenMissing(5L)).thenReturn(open);

        service.scanAndRecover();

        verify(eventMapper).markIgnored(55L, "FILE_REAPPEARED");
        verify(storageService, never()).restoreMissing(any(), any());
    }

    private void prepareSingleMissing(FileInfo fileInfo) {
        when(fileInfoMapper.selectNormalLocalAfterId(0L, 100)).thenReturn(List.of(fileInfo));
        when(storageService.inspect(fileInfo)).thenReturn(FileProtectionStorageService.FileState.MISSING);
        when(redissonClient.getLock(RedisKeyConstants.fileProtectionLock(fileInfo.getId()))).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(fileInfoMapper.selectNormalById(fileInfo.getId())).thenReturn(fileInfo);
    }

    private FileInfo normalFile(long id) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setFileMd5("0123456789abcdef0123456789abcdef");
        fileInfo.setFileSize(12L);
        fileInfo.setStorageType(FileInfo.STORAGE_TYPE_LOCAL);
        fileInfo.setStoragePath("C:/uploads/file-" + id + ".txt");
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }

    private FileIntegrityEvent detectedEvent(long id, long fileId) {
        FileIntegrityEvent event = new FileIntegrityEvent();
        event.setId(id);
        event.setFileId(fileId);
        event.setEventType(FileIntegrityEvent.TYPE_MISSING);
        event.setStatus(FileIntegrityEvent.STATUS_DETECTED);
        event.setDetectedCount(1);
        event.setAttemptCount(0);
        return event;
    }

    private FileBackupRecord readyBackup(long id, long fileId) {
        FileBackupRecord backup = new FileBackupRecord();
        backup.setId(id);
        backup.setFileId(fileId);
        backup.setFileSize(12L);
        backup.setFileMd5("0123456789abcdef0123456789abcdef");
        backup.setFileSha256("a".repeat(64));
        backup.setBackupPath("D:/backup/hash");
        backup.setStatus(FileBackupRecord.STATUS_READY);
        return backup;
    }
}
