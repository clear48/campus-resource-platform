package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.FileBackupRecord;
import com.john.campus.entity.FileInfo;
import com.john.campus.exception.FileProtectionException;
import com.john.campus.mapper.FileBackupRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.service.FileBackupService;
import com.john.campus.service.FileProtectionStorageService;
import java.util.List;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 以MySQL为任务来源，分批、可重放地建立在线单文件副本。 */
@Service
public class FileBackupServiceImpl implements FileBackupService {

    private static final Logger log = LoggerFactory.getLogger(FileBackupServiceImpl.class);

    private final FileInfoMapper fileInfoMapper;
    private final FileBackupRecordMapper backupRecordMapper;
    private final FileProtectionStorageService storageService;
    private final RedissonClient redissonClient;
    private final int batchSize;

    public FileBackupServiceImpl(
            FileInfoMapper fileInfoMapper,
            FileBackupRecordMapper backupRecordMapper,
            FileProtectionStorageService storageService,
            @Lazy RedissonClient redissonClient,
            @Value("${app.file-protection.batch-size:200}") int batchSize) {
        this.fileInfoMapper = fileInfoMapper;
        this.backupRecordMapper = backupRecordMapper;
        this.storageService = storageService;
        this.redissonClient = redissonClient;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
    }

    @Override
    public void backupPendingFiles() {
        storageService.assertRootsReady();
        long lastId = 0L;
        while (true) {
            List<FileInfo> files = fileInfoMapper.selectWithoutReadyBackupAfterId(lastId, batchSize);
            if (files.isEmpty()) {
                return;
            }
            for (FileInfo fileInfo : files) {
                lastId = fileInfo.getId();
                backupOne(fileInfo);
            }
            if (files.size() < batchSize) {
                return;
            }
        }
    }

    private void backupOne(FileInfo candidate) {
        RLock lock;
        try {
            lock = redissonClient.getLock(RedisKeyConstants.fileProtectionLock(candidate.getId()));
            if (!lock.tryLock()) {
                return;
            }
        } catch (RuntimeException ex) {
            log.error("在线文件副本获取分布式锁失败: fileId={}", candidate.getId(), ex);
            return;
        }

        try {
            FileInfo current = fileInfoMapper.selectNormalById(candidate.getId());
            if (current == null || !Integer.valueOf(FileInfo.STORAGE_TYPE_LOCAL).equals(current.getStorageType())) {
                return;
            }
            FileBackupRecord existing = backupRecordMapper.selectByFileId(current.getId());
            if (existing != null && existing.isReady()) {
                return;
            }
            backupRecordMapper.upsertPending(current);
            FileProtectionStorageService.BackupArtifact artifact = storageService.createVerifiedBackup(current);
            backupRecordMapper.markReady(
                    current.getId(), artifact.backupPath(), artifact.fileSize(), artifact.fileMd5(), artifact.fileSha256());
        } catch (FileProtectionException ex) {
            backupRecordMapper.markFailed(candidate.getId(), ex.getErrorCode());
            log.warn("在线文件副本创建失败: fileId={}, errorCode={}", candidate.getId(), ex.getErrorCode());
        } catch (RuntimeException ex) {
            backupRecordMapper.markFailed(candidate.getId(), "BACKUP_UNEXPECTED_ERROR");
            log.error("在线文件副本创建发生未预期异常: fileId={}", candidate.getId(), ex);
        } finally {
            releaseLock(lock, candidate.getId());
        }
    }

    private void releaseLock(RLock lock, long fileId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.error("在线文件副本释放分布式锁失败: fileId={}", fileId, ex);
        }
    }
}
