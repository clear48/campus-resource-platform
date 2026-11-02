package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.entity.FileInfo;
import com.john.campus.exception.BusinessException;
import com.john.campus.exception.FileProtectionException;
import com.john.campus.service.FileAvailabilityService;
import com.john.campus.service.FileProtectionStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文件保护业务门禁：保护功能未启用时完全兼容原流程，启用后失败关闭。
 */
@Service
public class FileAvailabilityServiceImpl implements FileAvailabilityService {

    private final FileProtectionStorageService fileProtectionStorageService;
    private final boolean protectionEnabled;

    public FileAvailabilityServiceImpl(
            FileProtectionStorageService fileProtectionStorageService,
            @Value("${app.file-protection.enabled:false}") boolean protectionEnabled) {
        this.fileProtectionStorageService = fileProtectionStorageService;
        this.protectionEnabled = protectionEnabled;
    }

    @Override
    public void requireAvailable(FileInfo fileInfo) {
        if (!protectionEnabled) {
            return;
        }

        try {
            FileProtectionStorageService.FileState state = fileProtectionStorageService.inspect(fileInfo);
            if (state == FileProtectionStorageService.FileState.HEALTHY) {
                return;
            }
        } catch (FileProtectionException ex) {
            // 根目录身份、路径或 IO 异常都按暂不可用失败关闭，不能绕过保护继续读写。
        }
        throw new BusinessException(ErrorCode.FILE_TEMPORARILY_UNAVAILABLE);
    }
}
