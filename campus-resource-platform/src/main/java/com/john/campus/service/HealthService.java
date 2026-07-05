package com.john.campus.service;

import com.john.campus.vo.HealthVO;

/**
 * 健康检查服务，后续暴露 HTTP 健康接口时可复用。
 */
public interface HealthService {

    /**
     * 返回当前应用的健康状态快照。
     */
    HealthVO check();
}
