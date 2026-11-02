package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.john.campus.entity.FileInfo;
import com.john.campus.entity.FileBackupRecord;
import com.john.campus.exception.FileProtectionException;
import com.john.campus.service.impl.FileProtectionStorageServiceImpl;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证在线副本只在根身份、数据库MD5和SHA-256都可信时发布。 */
class FileProtectionStorageServiceImplTest {

    @TempDir
    Path tempRoot;

    private Path storageRoot;
    private Path backupRoot;
    private FileProtectionStorageServiceImpl storageService;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Files.createDirectory(tempRoot.resolve("uploads"));
        backupRoot = Files.createDirectory(tempRoot.resolve("backups"));
        Files.writeString(storageRoot.resolve(FileProtectionStorageServiceImpl.STORAGE_MARKER), "storage-test");
        Files.writeString(backupRoot.resolve(FileProtectionStorageServiceImpl.BACKUP_MARKER), "backup-test");
        storageService = new FileProtectionStorageServiceImpl(
                storageRoot.toString(), backupRoot.toString(), "storage-test", "backup-test");
    }

    @Test
    void shouldCreateAndReuseContentAddressedVerifiedBackup() throws Exception {
        byte[] content = "online-backup-content".getBytes(StandardCharsets.UTF_8);
        Path source = storageRoot.resolve("file.txt");
        Files.write(source, content);
        FileInfo fileInfo = normalFile(1L, source, content);

        FileProtectionStorageService.BackupArtifact first = storageService.createVerifiedBackup(fileInfo);
        FileProtectionStorageService.BackupArtifact second = storageService.createVerifiedBackup(fileInfo);

        assertThat(second.backupPath()).isEqualTo(first.backupPath());
        assertThat(Path.of(first.backupPath())).hasBinaryContent(content);
        assertThat(first.fileSha256()).hasSize(64);
        try (var files = Files.walk(backupRoot)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
        }
    }

    @Test
    void shouldRejectSourceWhoseMd5NoLongerMatchesDatabase() throws Exception {
        byte[] content = "changed-content".getBytes(StandardCharsets.UTF_8);
        Path source = storageRoot.resolve("changed.txt");
        Files.write(source, content);
        FileInfo fileInfo = normalFile(2L, source, content);
        fileInfo.setFileMd5("0".repeat(32));

        assertThatThrownBy(() -> storageService.createVerifiedBackup(fileInfo))
                .isInstanceOf(FileProtectionException.class)
                .extracting(ex -> ((FileProtectionException) ex).getErrorCode())
                .isEqualTo("SOURCE_MD5_MISMATCH");
    }

    @Test
    void shouldRejectMissingOrWrongRootIdentity() throws Exception {
        Files.delete(backupRoot.resolve(FileProtectionStorageServiceImpl.BACKUP_MARKER));

        assertThatThrownBy(storageService::assertRootsReady)
                .isInstanceOf(FileProtectionException.class)
                .extracting(ex -> ((FileProtectionException) ex).getErrorCode())
                .isEqualTo("ROOT_MARKER_MISSING");
    }

    @Test
    void shouldRestoreConfirmedMissingFileWithoutStoppingOrOverwriting() throws Exception {
        byte[] content = "restore-online-content".getBytes(StandardCharsets.UTF_8);
        Path source = storageRoot.resolve("restore.txt");
        Files.write(source, content);
        FileInfo fileInfo = normalFile(3L, source, content);
        FileProtectionStorageService.BackupArtifact artifact = storageService.createVerifiedBackup(fileInfo);
        FileBackupRecord record = readyRecord(fileInfo, artifact);
        Files.delete(source);

        assertThat(storageService.inspect(fileInfo)).isEqualTo(FileProtectionStorageService.FileState.MISSING);
        assertThat(storageService.restoreMissing(fileInfo, record)).isTrue();
        assertThat(source).hasBinaryContent(content);

        Files.writeString(source, "unexpected-existing-content");
        assertThatThrownBy(() -> storageService.restoreMissing(fileInfo, record))
                .isInstanceOf(FileProtectionException.class)
                .extracting(ex -> ((FileProtectionException) ex).getErrorCode())
                .isEqualTo("RESTORE_TARGET_EXISTS");
        assertThat(Files.readString(source)).isEqualTo("unexpected-existing-content");
    }

    private FileInfo normalFile(long id, Path path, byte[] content) throws Exception {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(id);
        fileInfo.setFileMd5(digest("MD5", content));
        fileInfo.setFileSize((long) content.length);
        fileInfo.setStorageType(FileInfo.STORAGE_TYPE_LOCAL);
        fileInfo.setStoragePath(path.toString());
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }

    private String digest(String algorithm, byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
    }

    private FileBackupRecord readyRecord(
            FileInfo fileInfo, FileProtectionStorageService.BackupArtifact artifact) {
        FileBackupRecord record = new FileBackupRecord();
        record.setId(30L);
        record.setFileId(fileInfo.getId());
        record.setBackupPath(artifact.backupPath());
        record.setFileSize(artifact.fileSize());
        record.setFileMd5(artifact.fileMd5());
        record.setFileSha256(artifact.fileSha256());
        record.setStatus(FileBackupRecord.STATUS_READY);
        return record;
    }
}
