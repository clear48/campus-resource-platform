package com.john.campus.service.impl;

import com.john.campus.entity.FileInfo;
import com.john.campus.exception.FileProtectionException;
import com.john.campus.service.FileProtectionStorageService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地不可变文件副本实现：根目录身份校验后复制到临时文件，摘要通过才无覆盖发布。
 */
@Service
public class FileProtectionStorageServiceImpl implements FileProtectionStorageService {

    public static final String STORAGE_MARKER = ".crp-storage-id";
    public static final String BACKUP_MARKER = ".crp-backup-id";

    private final Path storageRoot;
    private final Path backupRoot;
    private final String expectedStorageId;
    private final String expectedBackupId;

    public FileProtectionStorageServiceImpl(
            @Value("${app.upload.storage-path}") String storagePath,
            @Value("${app.file-protection.backup-path}") String backupPath,
            @Value("${app.file-protection.storage-id:}") String expectedStorageId,
            @Value("${app.file-protection.backup-id:}") String expectedBackupId) {
        this.storageRoot = Paths.get(storagePath).toAbsolutePath().normalize();
        this.backupRoot = Paths.get(backupPath).toAbsolutePath().normalize();
        this.expectedStorageId = expectedStorageId == null ? "" : expectedStorageId.trim();
        this.expectedBackupId = expectedBackupId == null ? "" : expectedBackupId.trim();
        if (storageRoot.startsWith(backupRoot) || backupRoot.startsWith(storageRoot)) {
            throw new IllegalArgumentException("活动上传目录和在线副本目录不得互为父子目录");
        }
    }

    @Override
    public void assertRootsReady() {
        if (!StringUtils.hasText(expectedStorageId) || !StringUtils.hasText(expectedBackupId)) {
            throw failure("ROOT_ID_NOT_CONFIGURED", "文件保护根目录身份未配置");
        }
        assertRootIdentity(storageRoot, STORAGE_MARKER, expectedStorageId);
        assertRootIdentity(backupRoot, BACKUP_MARKER, expectedBackupId);
    }

    @Override
    public BackupArtifact createVerifiedBackup(FileInfo fileInfo) {
        assertRootsReady();
        validateFileInfo(fileInfo);
        Path source = resolveInsideRoot(fileInfo.getStoragePath(), storageRoot, "SOURCE_PATH_OUTSIDE_ROOT");
        assertRegularFile(source, "SOURCE_NOT_REGULAR");

        Path temporary = backupRoot.resolve(".backup-" + fileInfo.getId() + "-"
                + UUID.randomUUID().toString().replace("-", "") + ".part");
        try {
            Digests copied = copyAndDigest(source, temporary);
            if (copied.bytes() != fileInfo.getFileSize()) {
                throw failure("SOURCE_SIZE_MISMATCH", "活动文件大小与数据库不一致");
            }
            if (!copied.md5().equalsIgnoreCase(fileInfo.getFileMd5())) {
                throw failure("SOURCE_MD5_MISMATCH", "活动文件MD5与数据库不一致");
            }

            Path targetDirectory = backupRoot.resolve(copied.sha256().substring(0, 2));
            Files.createDirectories(targetDirectory);
            assertNoSymbolicLinks(backupRoot, targetDirectory);
            Path target = targetDirectory.resolve(copied.sha256());
            publishOrReuse(temporary, target, copied);
            target.toFile().setReadOnly();
            return new BackupArtifact(target.toString(), copied.bytes(), copied.md5(), copied.sha256());
        } catch (FileProtectionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure("BACKUP_IO_ERROR", "创建在线单文件副本失败", ex);
        } finally {
            deleteTemporaryQuietly(temporary);
        }
    }

    private void validateFileInfo(FileInfo fileInfo) {
        if (fileInfo == null || fileInfo.getId() == null || fileInfo.getFileSize() == null
                || fileInfo.getFileSize() < 0 || !StringUtils.hasText(fileInfo.getFileMd5())) {
            throw failure("FILE_METADATA_INVALID", "文件元数据不完整");
        }
        if (!Integer.valueOf(FileInfo.STORAGE_TYPE_LOCAL).equals(fileInfo.getStorageType()) || !fileInfo.isNormal()) {
            throw failure("FILE_NOT_PROTECTABLE", "只有正常本地文件允许创建在线副本");
        }
    }

    private void assertRootIdentity(Path root, String markerName, String expectedId) {
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw failure("ROOT_UNAVAILABLE", "文件保护根目录不可用");
            }
            Path marker = root.resolve(markerName);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) {
                throw failure("ROOT_MARKER_MISSING", "文件保护根目录身份文件不存在");
            }
            String actualId = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (!expectedId.equals(actualId)) {
                throw failure("ROOT_MARKER_MISMATCH", "文件保护根目录身份不匹配");
            }
        } catch (FileProtectionException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure("ROOT_UNAVAILABLE", "读取文件保护根目录身份失败", ex);
        }
    }

    private Path resolveInsideRoot(String rawPath, Path root, String errorCode) {
        if (!StringUtils.hasText(rawPath)) {
            throw failure(errorCode, "文件路径为空");
        }
        Path candidate = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw failure(errorCode, "文件路径越过受控根目录");
        }
        assertNoSymbolicLinks(root, candidate.getParent());
        return candidate;
    }

    private void assertRegularFile(Path path, String errorCode) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw failure(errorCode, "目标不是普通文件");
        }
    }

    private void assertNoSymbolicLinks(Path root, Path candidate) {
        if (candidate == null || !candidate.startsWith(root)) {
            throw failure("SYMLINK_OR_PATH_ESCAPE", "目录路径越界");
        }
        Path current = root;
        Path relative = root.relativize(candidate);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw failure("SYMLINK_OR_PATH_ESCAPE", "受控路径包含符号链接");
            }
        }
    }

    private Digests copyAndDigest(Path source, Path target) throws IOException {
        MessageDigest md5 = digest("MD5");
        MessageDigest sha256 = digest("SHA-256");
        long total = 0;
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                md5.update(buffer.array(), 0, read);
                sha256.update(buffer.array(), 0, read);
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                buffer.clear();
            }
            output.force(true);
        }
        return new Digests(total, HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha256.digest()));
    }

    private void publishOrReuse(Path temporary, Path target, Digests expected) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingBackup(target, expected);
            return;
        }
        try {
            // 不使用REPLACE_EXISTING；内容寻址目标一旦出现，只允许校验复用，绝不能覆盖。
            Files.move(temporary, target);
        } catch (FileAlreadyExistsException ex) {
            verifyExistingBackup(target, expected);
        }
    }

    private void verifyExistingBackup(Path target, Digests expected) throws IOException {
        assertRegularFile(target, "BACKUP_NOT_REGULAR");
        Digests actual = digestFile(target);
        if (actual.bytes() != expected.bytes() || !actual.sha256().equals(expected.sha256())) {
            throw failure("BACKUP_CONTENT_CONFLICT", "同一摘要路径已有不一致副本");
        }
    }

    private Digests digestFile(Path file) throws IOException {
        MessageDigest md5 = digest("MD5");
        MessageDigest sha256 = digest("SHA-256");
        long total = 0;
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                md5.update(buffer.array(), 0, read);
                sha256.update(buffer.array(), 0, read);
                buffer.clear();
            }
        }
        return new Digests(total, HexFormat.of().formatHex(md5.digest()), HexFormat.of().formatHex(sha256.digest()));
    }

    private MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM缺少必需摘要算法: " + algorithm, ex);
        }
    }

    private void deleteTemporaryQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // 临时文件清理由后续巡检兜底；这里不能覆盖主异常。
        }
    }

    private FileProtectionException failure(String code, String message) {
        return new FileProtectionException(code, message);
    }

    private FileProtectionException failure(String code, String message, Throwable cause) {
        return new FileProtectionException(code, message, cause);
    }

    private record Digests(long bytes, String md5, String sha256) {
    }
}
