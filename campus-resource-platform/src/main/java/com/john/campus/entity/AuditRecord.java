package com.john.campus.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 审核记录实体，对应 audit_record 表，用于追踪管理员对资料状态的每一次变更。
 */
@Getter
@Setter
public class AuditRecord extends BaseEntity {

    /**
     * 审核通过动作：待审核资料进入公开消费链路。
     */
    public static final int ACTION_APPROVE = 1;
    /**
     * 审核拒绝动作：待审核资料被驳回，并记录拒绝原因。
     */
    public static final int ACTION_REJECT = 2;
    /**
     * 下架动作：已通过资料退出公开消费链路。
     */
    public static final int ACTION_OFFLINE = 3;

    /**
     * 被审核资料 ID，逻辑关联 resource.id。
     */
    private Long resourceId;
    /**
     * 执行审核动作的管理员 ID，必须来自登录上下文，不能由前端传入。
     */
    private Long auditorId;
    /**
     * 审核动作类型，取值必须使用 ACTION_* 常量，避免业务代码散落魔法值。
     */
    private Integer actionType;
    /**
     * 操作前资料状态，用于还原审核状态机流转轨迹。
     */
    private Integer beforeStatus;
    /**
     * 操作后资料状态，用于审计本次动作最终把资料推进到哪个状态。
     */
    private Integer afterStatus;
    /**
     * 审核意见、拒绝原因或下架原因；拒绝和下架场景必须填写。
     */
    private String auditReason;

    /**
     * 当前记录是否为审核通过动作。
     */
    public boolean isApprove() {
        return Integer.valueOf(ACTION_APPROVE).equals(actionType);
    }

    /**
     * 当前记录是否为审核拒绝动作。
     */
    public boolean isReject() {
        return Integer.valueOf(ACTION_REJECT).equals(actionType);
    }

    /**
     * 当前记录是否为下架动作。
     */
    public boolean isOffline() {
        return Integer.valueOf(ACTION_OFFLINE).equals(actionType);
    }
}
