package com.john.campus.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import org.apache.ibatis.annotations.Param;

/**
 * 下载增量同步幂等明细的数据访问入口。
 *
 * <p>同一 Redis 隔离批次内的资料增量只能插入一次；该唯一约束是 Redis 确认失败后安全重试的最终兜底。</p>
 */
public interface DownloadDeltaSyncItemMapper {

    /**
     * 尝试写入本批次资料增量的幂等记录。返回 1 表示首次处理，返回 0 表示已由同一批次处理过。
     */
    int insertIgnore(
            @Param("batchId") String batchId,
            @Param("resourceId") Long resourceId,
            @Param("delta") Long delta);

    /** 查询已持久化的增量，用于防止异常 Redis 数据以同一批次标识覆盖既有记录。 */
    Long selectDelta(@Param("batchId") String batchId, @Param("resourceId") Long resourceId);

    /**
     * Redis HDEL 成功后标记已确认的幂等记录。记录暂不删除，便于异常排查与后续按保留期清理。
     */
    int markConfirmed(
            @Param("batchId") String batchId,
            @Param("resourceIds") Collection<Long> resourceIds,
            @Param("confirmedAt") LocalDateTime confirmedAt);
}
