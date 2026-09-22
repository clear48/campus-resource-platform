package com.john.campus.service;

import com.john.campus.vo.HealthVO;
import com.john.campus.vo.ReadinessVO;

/**
 * 健康检查服务，后续暴露 HTTP 健康接口时可复用。
 */
public interface HealthService {

    /**
     * 返回当前应用的健康状态快照。
     */
    HealthVO check();

    /**
     * 检查实例接收业务流量所依赖的 MySQL、Redis 与上传存储是否可用。
     */
    ReadinessVO checkReadiness();
}
