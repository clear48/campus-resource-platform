package com.john.campus.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 资料实体，对应 resource 表，承载资料业务信息、审核状态和统计快照。
 */
@Getter
@Setter
public class Resource extends BaseEntity {

    /**
     * 待审核：资料创建后的默认状态，不进入公开搜索和下载范围。
     */
    public static final int STATUS_PENDING_REVIEW = 0;
    /**
     * 审核通过：资料可被公开查看、搜索和下载。
     */
    public static final int STATUS_APPROVED = 1;
    /**
     * 审核拒绝：资料仅上传者和管理员可见，需展示拒绝原因。
     */
    public static final int STATUS_REJECTED = 2;
    /**
     * 已下架：曾经通过审核，但当前不再公开展示或下载。
     */
    public static final int STATUS_OFFLINE = 3;
    /**
     * 已删除：逻辑删除状态，保留数据但不参与普通业务查询。
     */
    public static final int STATUS_DELETED = 4;

    /**
     * 课件类型。
     */
    public static final int TYPE_COURSEWARE = 1;
    /**
     * 笔记类型。
     */
    public static final int TYPE_NOTE = 2;
    /**
     * 真题类型。
     */
    public static final int TYPE_EXAM = 3;
    /**
     * 实验报告类型。
     */
    public static final int TYPE_LAB_REPORT = 4;
    /**
     * 课程设计类型。
     */
    public static final int TYPE_COURSE_DESIGN = 5;
    /**
     * 其他类型，用于无法归入固定分类的资料。
     */
    public static final int TYPE_OTHER = 99;

    /**
     * 资料标题，面向搜索、列表和详情展示。
     */
    private String title;
    /**
     * 资料简介，可为空，用于补充资料内容说明。
     */
    private String description;
    /**
     * 分类 ID，逻辑关联 category.id，创建资料时必须校验分类可用。
     */
    private Long categoryId;
    /**
     * 课程名称，后续搜索和筛选会依赖该字段。
     */
    private String courseName;
    /**
     * 资料类型，必须命中 TYPE_* 常量之一。
     */
    private Integer resourceType;
    /**
     * 标签字符串，首版按逗号分隔保存，后续可演进为独立标签表。
     */
    private String tags;
    /**
     * 文件 ID，逻辑关联 file_info.id，下载模块会据此定位物理文件。
     */
    private Long fileId;
    /**
     * 上传用户 ID，来自登录上下文，不接受前端传入。
     */
    private Long uploaderId;
    /**
     * 审核状态，控制资料是否可公开查看、搜索、下载和收藏。
     */
    private Integer status;
    /**
     * 最近一次审核拒绝原因，仅在拒绝状态或历史展示中使用。
     */
    private String rejectReason;
    /**
     * 最近一次下架原因，仅在下架状态或后台审计中使用。
     */
    private String offlineReason;
    /**
     * 浏览次数，后续可由详情访问或统计模块维护。
     */
    private Long viewCount;
    /**
     * 下载次数，设计上由 Redis 增量定时同步到 MySQL。
     */
    private Long downloadCount;
    /**
     * 收藏次数，收藏模块会维护该统计快照。
     */
    private Long favoriteCount;
    /**
     * 热度分快照，后续可由 Redis 排行榜或定时任务回写。
     */
    private BigDecimal hotScore;
    /**
     * 审核通过时间，用于排序、展示和后续搜索可见性判断。
     */
    private LocalDateTime approvedAt;
    /**
     * 下架时间，用于后台追踪资料生命周期。
     */
    private LocalDateTime offlineAt;

    /**
     * 是否待审核，审核模块会以该状态作为可审核入口。
     */
    public boolean isPendingReview() {
        return Integer.valueOf(STATUS_PENDING_REVIEW).equals(status);
    }

    /**
     * 是否审核通过，公开详情、搜索、下载和收藏都依赖该判断。
     */
    public boolean isApproved() {
        return Integer.valueOf(STATUS_APPROVED).equals(status);
    }

    /**
     * 是否审核拒绝，上传者查看失败原因时会使用。
     */
    public boolean isRejected() {
        return Integer.valueOf(STATUS_REJECTED).equals(status);
    }

    /**
     * 是否已下架，下架资料不能继续公开消费。
     */
    public boolean isOffline() {
        return Integer.valueOf(STATUS_OFFLINE).equals(status);
    }

    /**
     * 是否已删除，普通业务查询应排除该状态。
     */
    public boolean isDeleted() {
        return Integer.valueOf(STATUS_DELETED).equals(status);
    }

    /**
     * 是否对公众可见，当前只有审核通过资料能进入公开详情和搜索结果。
     */
    public boolean isVisibleToPublic() {
        return isApproved();
    }
}
