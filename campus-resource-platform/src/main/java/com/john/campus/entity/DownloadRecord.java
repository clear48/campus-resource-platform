package com.john.campus.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 下载记录实体，对应 download_record 表，用于记录每一次下载行为，支撑审计、风控和「我的下载记录」查询。
 * 该表只有 created_at，没有 updated_at；虽继承 BaseEntity，但 Mapper 不映射 updated_at 列。
 */
@Getter
@Setter
public class DownloadRecord extends BaseEntity {

    /**
     * 下载成功：文件流正常返回，计入下载行为。
     */
    public static final int STATUS_SUCCESS = 1;
    /**
     * 下载失败：文件缺失或读取异常等，记录失败原因便于排查。
     */
    public static final int STATUS_FAIL = 2;

    /**
     * 下载用户 ID，逻辑关联 user.id，必须来自登录上下文，不接受前端传入。
     */
    private Long userId;
    /**
     * 被下载资料 ID，逻辑关联 resource.id。
     */
    private Long resourceId;
    /**
     * 被下载文件 ID，逻辑关联 file_info.id，下载文件流时据此定位物理文件。
     */
    private Long fileId;
    /**
     * 用户 IP，兼容 IPv4 和 IPv6，用于下载风控和审计。
     */
    private String userIp;
    /**
     * 浏览器或客户端信息，可为空，用于审计。
     */
    private String userAgent;
    /**
     * 下载状态，取值必须使用 STATUS_* 常量，避免业务代码散落魔法值。
     */
    private Integer downloadStatus;
    /**
     * 下载失败原因，仅在失败场景填写。
     */
    private String failReason;

    /**
     * 当前记录是否为下载成功，成功记录才参与下载量统计。
     */
    public boolean isSuccess() {
        return Integer.valueOf(STATUS_SUCCESS).equals(downloadStatus);
    }
}
