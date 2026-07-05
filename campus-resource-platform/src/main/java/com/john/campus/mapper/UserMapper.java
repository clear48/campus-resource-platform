package com.john.campus.mapper;

import com.john.campus.entity.User;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表数据访问接口，SQL 统一维护在 UserMapper.xml。
 */
public interface UserMapper {

    /**
     * 按用户名查询用户，用于注册去重和登录认证。
     */
    User selectByUsername(@Param("username") String username);

    /**
     * 按主键查询用户，用于当前用户信息和后续业务权限校验。
     */
    User selectById(@Param("id") Long id);

    /**
     * 插入新用户，数据库自增主键会回填到 user.id。
     */
    int insert(User user);

    /**
     * 登录成功后更新最近登录时间，不影响密码和账号基础信息。
     */
    int updateLastLoginAt(@Param("id") Long id, @Param("lastLoginAt") LocalDateTime lastLoginAt);
}
