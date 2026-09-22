package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
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
    /**
     * 单后端实例内串行化容量检查和写入，避免并发请求同时通过水位检查后共同耗尽磁盘。
     */
    private final ReentrantLock storageWriteLock = new ReentrantLock();
    private final long minFreeSpaceBytes;

    public FileStorageServiceImpl(
            @Value("${app.upload.storage-path}") String storagePath,
            @Value("${app.upload.min-free-space-bytes}") long minFreeSpaceBytes) {
        // 统一解析为规范化绝对路径，作为后续路径穿越校验的基准目录。
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.minFreeSpaceBytes = minFreeSpaceBytes;
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
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        storageWriteLock.lock();
        try {
            Files.createDirectories(storageRoot);
            ensureEnoughSpace(file.getSize());
            // 先完整写入同目录临时文件，避免读取方观察到半文件；UUID 文件名和 CREATE_NEW 防止覆盖。
            try (InputStream inputStream = file.getInputStream();
                    var outputStream = Files.newOutputStream(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                inputStream.transferTo(outputStream);
            }
            moveCompletedFile(temporary, target);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件保存失败");
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupEx) {
                log.warn("上传临时文件清理失败: {}", cleanupEx.getClass().getSimpleName());
            }
            storageWriteLock.unlock();
        }
        return new StoredFile(storedName, target.toString());
    }

    private void ensureEnoughSpace(long incomingFileSize) throws IOException {
        long usableSpace = Files.getFileStore(storageRoot).getUsableSpace();
        long requiredSpace = incomingFileSize > Long.MAX_VALUE - minFreeSpaceBytes
                ? Long.MAX_VALUE
                : incomingFileSize + minFreeSpaceBytes;
        if (usableSpace < requiredSpace) {
            throw new BusinessException(ErrorCode.STORAGE_INSUFFICIENT, "存储空间不足，暂时无法上传文件");
        }
    }

    private void moveCompletedFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            // 同目录普通移动仍不会暴露复制中的半文件；目标名为随机 UUID，禁止覆盖既有文件。
            Files.move(temporary, target);
        }
    }

    /**
     * 按存储路径读取文件流：校验路径安全 → 确认文件存在且可读 → 打开输入流。
     * 调用方负责在使用完毕后关闭 inputStream，避免文件句柄泄漏。
     */
    @Override
    public FileResource loadAsResource(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件存储路径不能为空");
        }

        Path filePath = validateStoragePath(storagePath);
        if (!Files.exists(filePath)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        if (!Files.isReadable(filePath)) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件不可读");
        }

        try {
            long fileSize = Files.size(filePath);
            InputStream inputStream = Files.newInputStream(filePath);
            return new FileResource(inputStream, fileSize);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件读取失败");
        }
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

    /**
     * 校验外部传入的存储路径仍在根目录内，防止路径穿越读取任意文件。
     * 与 resolveSafeTarget 的区别：入参是已保存的完整路径，只需做规范化与边界校验。
     */
    private Path validateStoragePath(String storagePath) {
        Path filePath = Paths.get(storagePath).toAbsolutePath().normalize();
        if (!filePath.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法的文件读取路径");
        }
        return filePath;
    }
}
