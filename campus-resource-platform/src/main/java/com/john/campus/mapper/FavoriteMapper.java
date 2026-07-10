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
     * 按收藏记录主键切换状态，取消后重新收藏时复用同一条历史记录。
     */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

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
