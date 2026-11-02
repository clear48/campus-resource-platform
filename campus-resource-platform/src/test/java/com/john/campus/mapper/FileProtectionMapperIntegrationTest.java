package com.john.campus.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.config.MyBatisConfig;
import com.john.campus.entity.FileBackupRecord;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.FileIntegrityEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

/** 使用真实MyBatis XML验证单文件保护的游标扫描、READY副本和异常状态流转。 */
@MybatisTest
@Import(MyBatisConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:file_protection_mapper_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mybatis.mapper-locations=classpath*:mapper/**/*.xml",
        "mybatis.type-aliases-package=com.john.campus.entity",
        "mybatis.configuration.map-underscore-to-camel-case=true"
})
@Sql(scripts = "/sql/file-protection-mapper-schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class FileProtectionMapperIntegrationTest {

    private static final String MD5 = "0123456789abcdef0123456789abcdef";

    @Autowired
    private FileInfoMapper fileInfoMapper;
    @Autowired
    private FileBackupRecordMapper backupRecordMapper;
    @Autowired
    private FileIntegrityEventMapper integrityEventMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldScanOnlyNormalLocalFilesWithoutReadyBackup() {
        insertFile(1L, FileInfo.STORAGE_TYPE_LOCAL, FileInfo.STATUS_NORMAL, MD5, 10L);
        insertFile(2L, FileInfo.STORAGE_TYPE_LOCAL, FileInfo.STATUS_DELETED,
                "1123456789abcdef0123456789abcdef", 11L);
        insertFile(3L, FileInfo.STORAGE_TYPE_MINIO, FileInfo.STATUS_NORMAL,
                "2123456789abcdef0123456789abcdef", 12L);

        List<FileInfo> normalLocal = fileInfoMapper.selectNormalLocalAfterId(0L, 10);
        List<FileInfo> withoutBackup = fileInfoMapper.selectWithoutReadyBackupAfterId(0L, 10);
        assertThat(normalLocal).extracting(FileInfo::getId).containsExactly(1L);
        assertThat(withoutBackup).extracting(FileInfo::getId).containsExactly(1L);

        backupRecordMapper.upsertPending(normalLocal.get(0));
        backupRecordMapper.markReady(1L, "D:/backup/hash", 10L, MD5, "a".repeat(64));

        FileBackupRecord backup = backupRecordMapper.selectByFileId(1L);
        assertThat(backup.isReady()).isTrue();
        assertThat(backup.getFileSha256()).hasSize(64);
        assertThat(fileInfoMapper.selectWithoutReadyBackupAfterId(0L, 10)).isEmpty();
    }

    @Test
    void shouldPersistMissingDetectionAndRecoveryState() {
        FileIntegrityEvent event = new FileIntegrityEvent();
        event.setFileId(9L);
        event.setEventType(FileIntegrityEvent.TYPE_MISSING);
        event.setStatus(FileIntegrityEvent.STATUS_DETECTED);
        event.setDetectedCount(1);
        event.setAttemptCount(0);
        integrityEventMapper.insert(event);

        FileIntegrityEvent detected = integrityEventMapper.selectLatestOpenMissing(9L);
        assertThat(detected.getId()).isEqualTo(event.getId());
        assertThat(integrityEventMapper.markConfirmedAndRestoring(event.getId(), 99L)).isEqualTo(1);
        assertThat(integrityEventMapper.markRecovered(event.getId())).isEqualTo(1);
        assertThat(integrityEventMapper.selectLatestOpenMissing(9L)).isNull();
    }

    private void insertFile(long id, int storageType, int status, String md5, long size) {
        jdbcTemplate.update("""
                INSERT INTO file_info (
                    id, file_md5, original_name, stored_name, file_ext, file_size,
                    storage_type, storage_path, uploader_id, ref_count, status
                ) VALUES (?, ?, ?, ?, 'txt', ?, ?, ?, 1, 1, ?)
                """,
                id, md5, "file-" + id + ".txt", "stored-" + id + ".txt", size,
                storageType, "C:/uploads/stored-" + id + ".txt", status);
    }
}
