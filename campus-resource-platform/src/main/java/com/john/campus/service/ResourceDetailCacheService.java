package com.john.campus.service;

import com.john.campus.vo.ResourceDetailVO;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 公开资料详情缓存边界，只处理与用户无关的详情快照和审核状态变更后的失效。
 */
public interface ResourceDetailCacheService {

    /**
     * 查询并校验公开详情缓存，缓存缺失、坏值或 Redis 故障时统一按未命中返回。
     */
    Optional<ResourceDetailVO> getPublicDetail(long resourceId);

    /**
     * 首次未命中后按资料加锁并二次检查；只有持锁线程可以执行加载后的缓存回填。
     * loader 的业务异常必须原样传播，缓存层不得重试业务加载。
     */
    ResourceDetailVO getOrLoad(long resourceId, Supplier<ResourceDetailVO> loader);

    /**
     * 写入公开详情快照；实现层必须确保用户态字段不会进入共享缓存。
     */
    void cachePublicDetail(ResourceDetailVO resourceDetail);

    /**
     * 审核状态提交后直接删除缓存；Redis 不可用时仅告警，依赖 TTL 兜底过期。
     */
    void invalidate(long resourceId);
}
