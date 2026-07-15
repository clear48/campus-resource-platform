package com.john.campus.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 用户文件授权关系访问入口，用于区分物理文件去重与业务侧可引用权限。
 */
public interface UserFileAuthorizationMapper {

    /**
     * 判断用户是否已经通过真实上传流程获得指定文件的引用权限。
     */
    boolean exists(@Param("userId") Long userId, @Param("fileId") Long fileId);

    /**
     * 幂等授予文件引用权限；并发重复授权由唯一索引收敛。
     */
    int insertIgnore(
            @Param("userId") Long userId,
            @Param("fileId") Long fileId,
            @Param("sourceType") Integer sourceType);
}
