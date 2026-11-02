package com.john.campus.task;

import com.john.campus.service.FileIntegrityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 文件完整性巡检调度入口；根目录或批量异常不会继续自动恢复。 */
@Component
@ConditionalOnProperty(name = "app.file-protection.enabled", havingValue = "true")
public class FileIntegrityTask {

    private static final Logger log = LoggerFactory.getLogger(FileIntegrityTask.class);
    private final FileIntegrityService fileIntegrityService;

    public FileIntegrityTask(FileIntegrityService fileIntegrityService) {
        this.fileIntegrityService = fileIntegrityService;
    }

    @Scheduled(fixedDelayString = "${app.file-protection.integrity-fixed-delay-ms:10000}")
    public void scanAndRecover() {
        try {
            fileIntegrityService.scanAndRecover();
        } catch (RuntimeException ex) {
            log.error("文件完整性巡检失败，本轮不执行任何恢复", ex);
        }
    }
}
