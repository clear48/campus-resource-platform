package com.john.campus.mapper;

import com.john.campus.entity.Favorite;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 收藏记录数据访问接口，只处理 favorite 表读写，不承载状态校验和幂等业务判断。
 */
public interface FavoriteMapper {

    /**
     * 新增收藏记录，数据库自增主键会回填到 favorite.id。
     */
    int insert(Favorite favorite);

    /**
     * 按用户和资料查询唯一收藏记录，用于判断首次收藏、重复收藏和重新收藏。
     */
    Favorite selectByUserAndResource(@Param("userId") Long userId, @Param("resourceId") Long resourceId);

    /**
     * 仅当记录仍处于 expectedStatus 时切换状态，避免并发收藏或取消时重复更新统计数。
     */
    int updateStatus(
            @Param("id") Long id,
            @Param("expectedStatus") Integer expectedStatus,
            @Param("status") Integer status);

    /**
     * 查询用户全部有效收藏的资料 ID，用于 Redis 收藏集合缓存重建。
     */
    List<Long> selectActiveResourceIdsByUser(@Param("userId") Long userId);

    /**
     * 按用户分页查询当前有效收藏，最近收藏的资料排在前面。
     */
    List<Favorite> selectByUser(
            @Param("userId") Long userId,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    /**
     * 统计用户当前有效收藏数，与 selectByUser 使用同一状态条件。
     */
    long countByUser(@Param("userId") Long userId);
}
