package com.john.campus.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 在线单文件副本记录；READY只表示副本已完成大小、MD5和SHA-256校验，不替代联合灾备。
 */
@Getter
@Setter
public class FileBackupRecord extends BaseEntity {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_READY = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_RETIRED = 3;

    private Long fileId;
    private String backupPath;
    private Long fileSize;
    private String fileMd5;
    private String fileSha256;
    private Integer status;
    private String lastErrorCode;
    private LocalDateTime backupCompletedAt;

    /** 只有完成全部摘要校验的副本才允许用于自动恢复。 */
    public boolean isReady() {
        return Integer.valueOf(STATUS_READY).equals(status);
    }
}
