package com.john.campus.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 文件信息实体，对应 file_info 表，保存物理文件元数据，与业务资料（resource）解耦。
 */
@Getter
@Setter
public class FileInfo extends BaseEntity {

    /**
     * 本地磁盘存储，当前文件上传模块只实现该类型。
     */
    public static final int STORAGE_TYPE_LOCAL = 1;
    /**
     * MinIO 对象存储，属后续扩展，当前为预留。
     */
    public static final int STORAGE_TYPE_MINIO = 2;
    /**
     * OSS 对象存储，属后续扩展，当前为预留。
     */
    public static final int STORAGE_TYPE_OSS = 3;

    /**
     * 文件正常可用。
     */
    public static final int STATUS_NORMAL = 1;
    /**
     * 文件已逻辑删除，不再对外提供。
     */
    public static final int STATUS_DELETED = 2;

    /**
     * 文件 MD5，与 fileSize 组合唯一，用于秒传去重。
     */
    private String fileMd5;
    /**
     * 用户上传时的原始文件名，仅展示用，不作为落盘名。
     */
    private String originalName;
    /**
     * 存储系统中的文件名，采用 UUID + 扩展名，避免覆盖和路径穿越。
     */
    private String storedName;
    /**
     * 文件扩展名，用于类型校验和下载时还原文件名。
     */
    private String fileExt;
    /**
     * 文件 MIME 类型，可为空。
     */
    private String mimeType;
    /**
     * 文件大小（字节），与 fileMd5 共同参与去重判断。
     */
    private Long fileSize;
    /**
     * 存储类型：1本地 2MinIO 3OSS。
     */
    private Integer storageType;
    /**
     * 文件存储路径或对象存储 Key。
     */
    private String storagePath;
    /**
     * 首次上传该文件的用户 ID，逻辑关联 user.id。
     */
    private Long uploaderId;
    /**
     * 引用次数，支持多份资料复用同一物理文件。
     */
    private Integer refCount;
    /**
     * 文件状态：1正常 2已删除。
     */
    private Integer status;

    /**
     * 集中封装可用状态判断，避免业务层散落魔法值。
     */
    public boolean isNormal() {
        return Integer.valueOf(STATUS_NORMAL).equals(status);
    }
}
