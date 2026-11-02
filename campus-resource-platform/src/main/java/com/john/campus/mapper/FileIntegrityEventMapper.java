package com.john.campus.mapper;

import com.john.campus.entity.FileIntegrityEvent;
import org.apache.ibatis.annotations.Param;

/** 文件异常事件和恢复结果的持久化入口。 */
public interface FileIntegrityEventMapper {

    FileIntegrityEvent selectLatestOpenMissing(@Param("fileId") Long fileId);

    int insert(FileIntegrityEvent event);

    int markConfirmedAndRestoring(@Param("id") Long id, @Param("backupRecordId") Long backupRecordId);

    int retryRestoring(@Param("id") Long id, @Param("backupRecordId") Long backupRecordId);

    int markRecovered(@Param("id") Long id);

    int markFailed(@Param("id") Long id, @Param("errorCode") String errorCode);

    int markIgnored(@Param("id") Long id, @Param("errorCode") String errorCode);
}
