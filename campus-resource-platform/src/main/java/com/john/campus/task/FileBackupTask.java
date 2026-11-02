package com.john.campus.task;

import com.john.campus.service.FileBackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在线单文件副本调度入口；功能默认关闭，任务异常只记录并等待下一轮重试。 */
@Component
@ConditionalOnProperty(name = "app.file-protection.enabled", havingValue = "true")
public class FileBackupTask {

    private static final Logger log = LoggerFactory.getLogger(FileBackupTask.class);
    private final FileBackupService fileBackupService;

    public FileBackupTask(FileBackupService fileBackupService) {
        this.fileBackupService = fileBackupService;
    }

    @Scheduled(fixedDelayString = "${app.file-protection.backup-fixed-delay-ms:30000}")
    public void backupPendingFiles() {
        try {
            fileBackupService.backupPendingFiles();
        } catch (RuntimeException ex) {
            log.error("在线单文件副本任务失败，等待下一轮重试", ex);
        }
    }
}
