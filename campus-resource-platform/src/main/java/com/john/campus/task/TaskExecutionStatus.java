package com.john.campus.task;

/**
 * 调度入口最近一次委派的执行状态。该状态只描述调度层是否正常返回，具体 Redis 降级或业务跳过仍由 Service 日志说明。
 */
public enum TaskExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
