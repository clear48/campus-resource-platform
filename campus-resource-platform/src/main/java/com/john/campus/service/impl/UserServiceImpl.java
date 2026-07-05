package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.User;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.UserMapper;
import com.john.campus.service.UserService;
import com.john.campus.vo.UserVO;
import org.springframework.stereotype.Service;

/**
 * 用户业务实现，当前负责根据登录上下文查询当前用户。
 */
@Service
public class UserServiceImpl implements UserService {

    /**
     * 用户表访问入口。
     */
    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 当前用户信息以数据库状态为准，不能只信任 JWT 中的历史状态。
     */
    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContextHolder.getRequiredUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            // Token 可能来自已删除用户，必须二次查库确认用户仍存在。
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if (!user.isNormal()) {
            // 用户被禁用后即使 Token 未过期，也不能继续访问受保护接口。
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }
        return toUserVO(user);
    }

    /**
     * 只返回前端需要的安全字段，不暴露密码哈希和手机号。
     */
    private UserVO toUserVO(User user) {
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );
    }
}
