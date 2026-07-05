package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.JwtUtils;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.dto.AuthLoginDTO;
import com.john.campus.dto.AuthRegisterDTO;
import com.john.campus.entity.User;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.UserMapper;
import com.john.campus.service.AuthService;
import com.john.campus.service.PasswordService;
import com.john.campus.vo.AuthLoginVO;
import com.john.campus.vo.UserVO;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 认证业务实现，负责用户注册、登录签发 JWT、退出登录写黑名单。
 */
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * 登录响应中的 Token 类型，前端请求时需要拼成 Authorization: Bearer {token}。
     */
    private static final String BEARER_TOKEN_TYPE = "Bearer";
    /**
     * 黑名单值本身不参与判断，Key 存在即表示 Token 失效。
     */
    private static final String TOKEN_BLACKLIST_VALUE = "logout";

    /**
     * 用户表访问入口，注册和登录都依赖 user 表。
     */
    private final UserMapper userMapper;
    /**
     * 密码服务负责 BCrypt 加密和校验，业务层不直接操作编码器。
     */
    private final PasswordService passwordService;
    /**
     * JWT 工具负责签发和解析 Token。
     */
    private final JwtUtils jwtUtils;
    /**
     * Redis 用于写入 Token 黑名单。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public AuthServiceImpl(
            UserMapper userMapper,
            PasswordService passwordService,
            JwtUtils jwtUtils,
            StringRedisTemplate stringRedisTemplate) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.jwtUtils = jwtUtils;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 注册默认创建学生账号；唯一性既做前置查询，也依赖数据库唯一索引兜底并发场景。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO register(AuthRegisterDTO request) {
        String username = normalizeRequired(request.getUsername());
        if (userMapper.selectByUsername(username) != null) {
            throw new BusinessException(ErrorCode.DATA_DUPLICATE, "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordService.encode(request.getPassword()));
        user.setNickname(normalizeRequired(request.getNickname()));
        user.setEmail(normalizeOptional(request.getEmail()));
        user.setPhone(normalizeOptional(request.getPhone()));
        user.setRole(User.ROLE_STUDENT);
        user.setStatus(User.STATUS_NORMAL);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 并发注册可能绕过前置查询，最终用数据库唯一索引保证不产生重复账号。
            throw new BusinessException(ErrorCode.DATA_DUPLICATE, "用户名、邮箱或手机号已存在");
        }

        return toUserVO(user);
    }

    /**
     * 登录成功后更新最近登录时间并签发 JWT；账号不存在和密码错误统一提示，避免账号枚举。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthLoginVO login(AuthLoginDTO request) {
        String username = normalizeRequired(request.getUsername());
        User user = userMapper.selectByUsername(username);
        if (user == null || !passwordService.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
        }
        if (!user.isNormal()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户已被禁用");
        }

        LocalDateTime now = LocalDateTime.now();
        // 最近登录时间属于用户状态变化，和登录成功保持在同一事务中。
        userMapper.updateLastLoginAt(user.getId(), now);
        user.setLastLoginAt(now);

        String token = jwtUtils.generateToken(user.getId(), user.getRole());
        return new AuthLoginVO(token, BEARER_TOKEN_TYPE, jwtUtils.getExpirationSeconds(), toUserVO(user));
    }

    /**
     * 退出登录通过写入 jti 黑名单让未过期 Token 立即失效。
     */
    @Override
    public void logout(String token) {
        String rawToken = removeBearerPrefix(token);
        String jti = jwtUtils.getJti(rawToken);
        long remainingSeconds = jwtUtils.getRemainingSeconds(rawToken);
        if (remainingSeconds <= 0) {
            // 已过期 Token 不再写入 Redis，避免浪费存储空间。
            return;
        }

        stringRedisTemplate.opsForValue().set(
                RedisKeyConstants.tokenBlacklist(jti),
                TOKEN_BLACKLIST_VALUE,
                Duration.ofSeconds(remainingSeconds)
        );
    }

    /**
     * 转换为对外用户信息，避免把 passwordHash、phone 等敏感或暂不需要字段返回给前端。
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

    /**
     * 必填字符串统一 trim，并在 Service 层兜底防御绕过 Validation 的调用。
     */
    private String normalizeRequired(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        }
        return value.trim();
    }

    /**
     * 可选字符串为空时统一转成 null，避免数据库唯一索引受空字符串干扰。
     */
    private String normalizeOptional(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 兼容 Controller 传入完整 Bearer 头或纯 Token 两种形式。
     */
    private String removeBearerPrefix(String token) {
        String rawToken = normalizeRequired(token);
        if (rawToken.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return rawToken.substring("Bearer ".length()).trim();
        }
        return rawToken;
    }
}
