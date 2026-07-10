package com.john.campus.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 收藏记录实体，对应 favorite 表，通过状态字段保留取消收藏历史。
 */
@Getter
@Setter
public class Favorite extends BaseEntity {

    /**
     * 已收藏状态，表示当前用户仍收藏该资料。
     */
    public static final int STATUS_FAVORITED = 1;

    /**
     * 已取消状态，保留历史记录以支持后续再次收藏。
     */
    public static final int STATUS_CANCELED = 0;

    /**
     * 收藏用户 ID，必须由登录上下文提供，不能由客户端指定。
     */
    private Long userId;

    /**
     * 被收藏资料 ID，逻辑关联 resource.id。
     */
    private Long resourceId;

    /**
     * 收藏状态，取值必须使用 STATUS_FAVORITED 或 STATUS_CANCELED。
     */
    private Integer status;
}
