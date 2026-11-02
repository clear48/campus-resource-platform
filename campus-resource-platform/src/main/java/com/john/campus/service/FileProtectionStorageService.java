package com.john.campus.service;

import com.john.campus.entity.FileInfo;

/** 负责受控目录内的副本创建和摘要校验，不承担数据库状态更新或调度。 */
public interface FileProtectionStorageService {

    /** 验证活动根和副本根身份文件、路径隔离及符号链接边界。 */
    void assertRootsReady();

    /** 创建或复用内容寻址副本，只有大小、MD5和SHA-256都通过后才返回。 */
    BackupArtifact createVerifiedBackup(FileInfo fileInfo);

    /** 已发布的不可变副本描述。 */
    record BackupArtifact(String backupPath, long fileSize, String fileMd5, String fileSha256) {
    }
}
