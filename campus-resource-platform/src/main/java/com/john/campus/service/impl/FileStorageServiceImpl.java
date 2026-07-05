package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件存储实现：把文件落盘到配置目录，计算 MD5、生成安全存储名并支持删除补偿。
 * 后续接入 MinIO / OSS 时可新增实现类，由 storage_type 区分，不影响调用方。
 */
@Service
public class FileStorageServiceImpl implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageServiceImpl.class);

    /**
     * 扩展名最大长度，与 file_info.file_ext 的 VARCHAR(20) 对齐，防止异常超长扩展名。
     */
    private static final int MAX_EXT_LENGTH = 16;

    /**
     * 文件落盘根目录，来自配置 app.upload.storage-path，规范化为绝对路径。
     */
    private final Path storageRoot;

    public FileStorageServiceImpl(@Value("${app.upload.storage-path}") String storagePath) {
        // 统一解析为规范化绝对路径，作为后续路径穿越校验的基准目录。
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
    }

    @Override
    public String calculateMd5(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return DigestUtils.md5DigestAsHex(inputStream);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件读取失败");
        }
    }

    @Override
    public String resolveExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }
        // 只取最后一段文件名，避免原始名中携带的路径分隔符参与后续处理。
        String pureName = Paths.get(originalFilename).getFileName().toString();
        int dotIndex = pureName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == pureName.length() - 1) {
            return "";
        }
        // 扩展名只保留字母和数字，杜绝借扩展名注入路径或特殊字符。
        String ext = pureName.substring(dotIndex + 1).toLowerCase().replaceAll("[^a-z0-9]", "");
        if (ext.length() > MAX_EXT_LENGTH) {
            return ext.substring(0, MAX_EXT_LENGTH);
        }
        return ext;
    }

    @Override
    public StoredFile store(MultipartFile file, String fileExt) {
        String storedName = buildStoredName(fileExt);
        Path target = resolveSafeTarget(storedName);
        try {
            Files.createDirectories(storageRoot);
            // 用独立输入流写盘，与计算 MD5 时的流互不影响。
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件保存失败");
        }
        return new StoredFile(storedName, target.toString());
    }

    @Override
    public void delete(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException ex) {
            // 补偿删除失败不应打断主流程，记录日志便于后续人工或定时任务清理。
            log.warn("删除文件失败: {}", storagePath, ex);
        }
    }

    /**
     * 生成存储文件名：UUID 去横线，拼接扩展名（无扩展名时只用 UUID）。
     */
    private String buildStoredName(String fileExt) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        if (!StringUtils.hasText(fileExt)) {
            return uuid;
        }
        return uuid + "." + fileExt;
    }

    /**
     * 解析落盘目标路径，并二次校验仍在存储根目录内，纵深防御路径穿越。
     */
    private Path resolveSafeTarget(String storedName) {
        Path target = storageRoot.resolve(storedName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的文件存储路径");
        }
        return target;
    }
}
