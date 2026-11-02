package com.john.campus.mapper;

import com.john.campus.entity.FileBackupRecord;
import com.john.campus.entity.FileInfo;
import org.apache.ibatis.annotations.Param;

/** 在线单文件副本记录访问入口。 */
public interface FileBackupRecordMapper {

    FileBackupRecord selectByFileId(@Param("fileId") Long fileId);

    int upsertPending(FileInfo fileInfo);

    int markReady(
            @Param("fileId") Long fileId,
            @Param("backupPath") String backupPath,
            @Param("fileSize") long fileSize,
            @Param("fileMd5") String fileMd5,
            @Param("fileSha256") String fileSha256);

    int markFailed(@Param("fileId") Long fileId, @Param("errorCode") String errorCode);

    int retireByFileId(@Param("fileId") Long fileId);
}
