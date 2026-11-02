package com.john.campus.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 文件异常检测和自动恢复审计记录，不保存异常堆栈或可对外暴露的绝对路径。 */
@Getter
@Setter
public class FileIntegrityEvent extends BaseEntity {

    public static final int TYPE_MISSING = 1;
    public static final int TYPE_SIZE_MISMATCH = 2;
    public static final int TYPE_HASH_MISMATCH = 3;
    public static final int TYPE_ROOT_UNAVAILABLE = 4;

    public static final int STATUS_DETECTED = 0;
    public static final int STATUS_CONFIRMED = 1;
    public static final int STATUS_RESTORING = 2;
    public static final int STATUS_RECOVERED = 3;
    public static final int STATUS_FAILED = 4;
    public static final int STATUS_IGNORED = 5;

    private Long fileId;
    private Long backupRecordId;
    private Integer eventType;
    private Integer status;
    private Integer detectedCount;
    private Integer attemptCount;
    private String errorCode;
    private LocalDateTime detectedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime recoveredAt;
}
