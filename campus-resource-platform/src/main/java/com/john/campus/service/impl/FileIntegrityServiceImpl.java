package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.FileBackupRecord;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.FileIntegrityEvent;
import com.john.campus.exception.FileProtectionException;
import com.john.campus.mapper.FileBackupRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.FileIntegrityEventMapper;
import com.john.campus.service.FileIntegrityService;
import com.john.campus.service.FileMd5CacheService;
import com.john.campus.service.FileProtectionStorageService;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 文件巡检恢复编排：先全局判断是否批量异常，再对单文件执行持久化二次确认、锁和原子恢复。
 */
@Service
public class FileIntegrityServiceImpl implements FileIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(FileIntegrityServiceImpl.class);

    private final FileInfoMapper fileInfoMapper;
    private final FileBackupRecordMapper backupRecordMapper;
    private final FileIntegrityEventMapper eventMapper;
    private final FileProtectionStorageService storageService;
    private final FileMd5CacheService md5CacheService;
    private final RedissonClient redissonClient;
    private final int batchSize;
    private final int missingCountThreshold;
    private final double missingRatioThreshold;
    private final int maxRecoveryAttempts;

    public FileIntegrityServiceImpl(
            FileInfoMapper fileInfoMapper,
            FileBackupRecordMapper backupRecordMapper,
            FileIntegrityEventMapper eventMapper,
            FileProtectionStorageService storageService,
            FileMd5CacheService md5CacheService,
            @Lazy RedissonClient redissonClient,
            @Value("${app.file-protection.batch-size:200}") int batchSize,
            @Value("${app.file-protection.missing-count-threshold:2}") int missingCountThreshold,
            @Value("${app.file-protection.missing-ratio-threshold:0.01}") double missingRatioThreshold,
            @Value("${app.file-protection.max-recovery-attempts:3}") int maxRecoveryAttempts) {
        this.fileInfoMapper = fileInfoMapper;
        this.backupRecordMapper = backupRecordMapper;
        this.eventMapper = eventMapper;
        this.storageService = storageService;
        this.md5CacheService = md5CacheService;
        this.redissonClient = redissonClient;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        this.missingCountThreshold = Math.max(2, missingCountThreshold);
        this.missingRatioThreshold = Math.max(0.0, Math.min(missingRatioThreshold, 1.0));
        this.maxRecoveryAttempts = Math.max(1, maxRecoveryAttempts);
    }

    @Override
    public void scanAndRecover() {
        storageService.assertRootsReady();
        ScanResult result = scanAllNormalLocalFiles();
        if (result.missingFiles().isEmpty()) {
            return;
        }
        if (shouldFuse(result)) {
            for (FileInfo fileInfo : result.missingFiles()) {
                ignoreOpenEvent(fileInfo.getId(), "BATCH_MISSING_FUSE");
                insertIgnoredEvent(fileInfo.getId(), FileIntegrityEvent.TYPE_MISSING, "BATCH_MISSING_FUSE");
            }
            log.error("文件自动恢复已熔断: missing={}, total={}",
                    result.missingFiles().size(), result.totalFiles());
            return;
        }
        for (FileInfo missing : result.missingFiles()) {
            handleConfirmedMissing(missing);
        }
    }

    private ScanResult scanAllNormalLocalFiles() {
        long lastId = 0L;
        long total = 0L;
        List<FileInfo> missing = new ArrayList<>();
        Set<Long> openMissingFileIds = new HashSet<>(eventMapper.selectOpenMissingFileIds());
        while (true) {
            List<FileInfo> files = fileInfoMapper.selectNormalLocalAfterId(lastId, batchSize);
            if (files.isEmpty()) {
                return new ScanResult(total, missing);
            }
            total += files.size();
            for (FileInfo fileInfo : files) {
                lastId = fileInfo.getId();
                FileProtectionStorageService.FileState state = storageService.inspect(fileInfo);
                if (state == FileProtectionStorageService.FileState.MISSING) {
                    missing.add(fileInfo);
                } else if (state == FileProtectionStorageService.FileState.SIZE_MISMATCH) {
                    insertIgnoredEvent(fileInfo.getId(), FileIntegrityEvent.TYPE_SIZE_MISMATCH, "SIZE_MISMATCH");
                } else if (state == FileProtectionStorageService.FileState.UNSAFE) {
                    insertIgnoredEvent(fileInfo.getId(), FileIntegrityEvent.TYPE_HASH_MISMATCH, "UNSAFE_FILE_TYPE");
                } else if (openMissingFileIds.contains(fileInfo.getId())) {
                    // 缺失必须连续两轮成立；中间恢复健康就关闭旧事件，未来再次缺失重新从第一轮计算。
                    ignoreOpenEvent(fileInfo.getId(), "FILE_REAPPEARED");
                }
            }
            if (files.size() < batchSize) {
                return new ScanResult(total, missing);
            }
        }
    }

    private boolean shouldFuse(ScanResult result) {
        int missing = result.missingFiles().size();
        if (missing >= missingCountThreshold) {
            return true;
        }
        // 单文件缺失始终允许进入二次确认；比例阈值只用于阻止多个异常被误当作独立误删。
        return missing > 1 && result.totalFiles() > 0
                && ((double) missing / result.totalFiles()) > missingRatioThreshold;
    }

    private void handleConfirmedMissing(FileInfo candidate) {
        RLock lock = acquireLock(candidate.getId());
        if (lock == null) {
            return;
        }
        try {
            FileInfo current = fileInfoMapper.selectNormalById(candidate.getId());
            if (!sameProtectableFile(candidate, current)) {
                ignoreOpenEvent(candidate.getId(), "FILE_METADATA_CHANGED");
                return;
            }
            FileProtectionStorageService.FileState currentState = storageService.inspect(current);
            if (currentState != FileProtectionStorageService.FileState.MISSING) {
                ignoreOpenEvent(current.getId(), "FILE_REAPPEARED_OR_CHANGED");
                return;
            }

            FileIntegrityEvent event = eventMapper.selectLatestOpenMissing(current.getId());
            if (event == null) {
                insertDetectedMissing(current.getId());
                return;
            }
            FileBackupRecord backup = backupRecordMapper.selectByFileId(current.getId());
            if (backup == null || !backup.isReady()) {
                eventMapper.markIgnored(event.getId(), "NO_READY_BACKUP");
                return;
            }
            if (!beginRecovery(event, backup.getId())) {
                return;
            }
            try {
                boolean restored = storageService.restoreMissing(current, backup);
                if (!restored) {
                    eventMapper.markIgnored(event.getId(), "FILE_REAPPEARED");
                    return;
                }
                eventMapper.markRecovered(event.getId());
                // 生命周期变化后清理MD5缓存，后续预检回源MySQL并重新确认可用性。
                md5CacheService.evict(current.getFileMd5(), current.getFileSize());
                log.info("在线单文件恢复完成: fileId={}", current.getId());
            } catch (FileProtectionException ex) {
                eventMapper.markFailed(event.getId(), ex.getErrorCode());
                log.error("在线单文件恢复失败: fileId={}, errorCode={}", current.getId(), ex.getErrorCode());
            } catch (RuntimeException ex) {
                eventMapper.markFailed(event.getId(), "RECOVERY_UNEXPECTED_ERROR");
                log.error("在线单文件恢复发生未预期异常: fileId={}", current.getId(), ex);
            }
        } finally {
            releaseLock(lock, candidate.getId());
        }
    }

    private boolean beginRecovery(FileIntegrityEvent event, Long backupRecordId) {
        if (Integer.valueOf(FileIntegrityEvent.STATUS_DETECTED).equals(event.getStatus())) {
            return eventMapper.markConfirmedAndRestoring(event.getId(), backupRecordId) == 1;
        }
        if (Integer.valueOf(FileIntegrityEvent.STATUS_RESTORING).equals(event.getStatus())) {
            eventMapper.markFailed(event.getId(), "INTERRUPTED_RECOVERY");
            return false;
        }
        if (Integer.valueOf(FileIntegrityEvent.STATUS_FAILED).equals(event.getStatus())) {
            if (event.getAttemptCount() >= maxRecoveryAttempts) {
                eventMapper.markIgnored(event.getId(), "MAX_RECOVERY_ATTEMPTS");
                return false;
            }
            return eventMapper.retryRestoring(event.getId(), backupRecordId) == 1;
        }
        return false;
    }

    private boolean sameProtectableFile(FileInfo expected, FileInfo current) {
        return current != null
                && Integer.valueOf(FileInfo.STORAGE_TYPE_LOCAL).equals(current.getStorageType())
                && current.isNormal()
                && expected.getStoragePath().equals(current.getStoragePath())
                && expected.getFileSize().equals(current.getFileSize())
                && expected.getFileMd5().equalsIgnoreCase(current.getFileMd5());
    }

    private RLock acquireLock(long fileId) {
        try {
            RLock lock = redissonClient.getLock(RedisKeyConstants.fileProtectionLock(fileId));
            return lock.tryLock() ? lock : null;
        } catch (RuntimeException ex) {
            log.error("文件巡检恢复获取分布式锁失败: fileId={}", fileId, ex);
            return null;
        }
    }

    private void releaseLock(RLock lock, long fileId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.error("文件巡检恢复释放分布式锁失败: fileId={}", fileId, ex);
        }
    }

    private void insertDetectedMissing(long fileId) {
        FileIntegrityEvent event = new FileIntegrityEvent();
        event.setFileId(fileId);
        event.setEventType(FileIntegrityEvent.TYPE_MISSING);
        event.setStatus(FileIntegrityEvent.STATUS_DETECTED);
        event.setDetectedCount(1);
        event.setAttemptCount(0);
        eventMapper.insert(event);
    }

    private void insertIgnoredEvent(long fileId, int eventType, String errorCode) {
        FileIntegrityEvent latest = eventMapper.selectLatestByFileAndType(fileId, eventType);
        if (latest != null && Integer.valueOf(FileIntegrityEvent.STATUS_IGNORED).equals(latest.getStatus())
                && errorCode.equals(latest.getErrorCode())) {
            return;
        }
        FileIntegrityEvent event = new FileIntegrityEvent();
        event.setFileId(fileId);
        event.setEventType(eventType);
        event.setStatus(FileIntegrityEvent.STATUS_IGNORED);
        event.setDetectedCount(1);
        event.setAttemptCount(0);
        event.setErrorCode(errorCode);
        eventMapper.insert(event);
    }

    private void ignoreOpenEvent(long fileId, String errorCode) {
        FileIntegrityEvent event = eventMapper.selectLatestOpenMissing(fileId);
        if (event != null) {
            eventMapper.markIgnored(event.getId(), errorCode);
        }
    }

    private record ScanResult(long totalFiles, List<FileInfo> missingFiles) {
    }
}
