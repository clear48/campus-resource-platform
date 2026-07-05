package com.john.campus.service.impl;

import com.john.campus.service.HealthService;
import com.john.campus.vo.HealthVO;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/**
 * 健康检查服务实现，返回应用名、状态和检查时间。
 */
@Service
public class HealthServiceImpl implements HealthService {

    /**
     * 当前仅做应用进程级检查，暂未探测 MySQL、Redis 等外部依赖。
     */
    @Override
    public HealthVO check() {
        return new HealthVO("campus-resource-platform", "UP", LocalDateTime.now());
    }
}
