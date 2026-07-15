package com.john.campus.common;

import com.john.campus.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * JWT 工具类，集中处理 Token 生成、解析和剩余有效期计算。
 */
@Component
public class JwtUtils {

    /**
     * 显式保存 userId，兼容后续 subject 规则变化时仍能稳定读取用户身份。
     */
    private static final String USER_ID_CLAIM = "userId";
    /**
     * 角色写入 Token，拦截器解析后可用于后续权限判断。
     */
    private static final String ROLE_CLAIM = "role";
    /**
     * HS256 至少需要 256 bit 密钥，启动时校验可以提前暴露配置风险。
     */
    private static final int HS256_MIN_SECRET_BYTES = 32;
    /**
     * 历史版本公开过的开发默认密钥。即使部署方显式设置了该值，也必须拒绝启动。
     */
    private static final String INSECURE_LEGACY_DEFAULT_SECRET =
            "campus-resource-platform-dev-secret-change-me";

    /**
     * 由配置密钥派生出的签名密钥，所有 Token 验签都依赖它。
     */
    private final SecretKey secretKey;
    /**
     * Access Token 有效期，登录响应和退出黑名单 TTL 都复用该配置。
     */
    private final long expirationSeconds;

    public JwtUtils(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-seconds}") long expirationSeconds) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalArgumentException("JWT secret must be configured");
        }
        if (INSECURE_LEGACY_DEFAULT_SECRET.equals(secret)) {
            // 公开默认值已不具备秘密性，显式配置它也不能作为兼容方案继续运行。
            throw new IllegalArgumentException("JWT secret must not use the insecure legacy default");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < HS256_MIN_SECRET_BYTES) {
            // 密钥过短会降低签名安全性，直接阻止应用以不安全配置启动。
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes for HS256");
        }
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 签发登录 Token，jti 用于后续退出登录时让单个 Token 立即失效。
     */
    public String generateToken(Long userId, Integer role) {
        // 去掉横线让 Redis 黑名单 Key 更短、更易读。
        String jti = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(USER_ID_CLAIM, userId)
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 校验并解析 Token，所有 JWT 异常统一转成业务未登录错误。
     */
    public JwtClaims parseToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Long userId = readUserId(claims);
            Integer role = readIntegerClaim(claims, ROLE_CLAIM);
            // 将第三方 Claims 转成项目内 record，降低业务代码和 JWT 库的耦合。
            return new JwtClaims(
                    userId,
                    role,
                    claims.getId(),
                    toLocalDateTime(claims.getIssuedAt()),
                    toLocalDateTime(claims.getExpiration())
            );
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 无效或已过期");
        }
    }

    /**
     * 快捷读取用户 ID，适用于只关心身份的调用方。
     */
    public Long getUserId(String token) {
        return parseToken(token).userId();
    }

    /**
     * 快捷读取用户角色，后续权限控制可复用。
     */
    public Integer getRole(String token) {
        return parseToken(token).role();
    }

    /**
     * 快捷读取 Token ID，退出登录写黑名单时使用。
     */
    public String getJti(String token) {
        return parseToken(token).jti();
    }

    /**
     * 计算 Token 剩余秒数，作为 Redis 黑名单 TTL，避免无意义长期占用内存。
     */
    public long getRemainingSeconds(String token) {
        LocalDateTime expiresAt = parseToken(token).expiresAt();
        long remainingSeconds = expiresAt.atZone(ZoneId.systemDefault()).toEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(remainingSeconds, 0);
    }

    /**
     * 登录响应需要告诉客户端 Token 有效期，因此暴露配置值。
     */
    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    /**
     * 优先读取自定义 userId Claim，缺失时回退 subject，兼容旧 Token 或不同签发策略。
     */
    private Long readUserId(Claims claims) {
        Long userId = readLongClaim(claims, USER_ID_CLAIM);
        if (userId != null) {
            return userId;
        }
        String subject = claims.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("Token subject is empty");
        }
        return Long.valueOf(subject);
    }

    /**
     * JWT 库反序列化数字 Claim 时可能返回不同 Number 子类，这里统一转为 Long。
     */
    private Long readLongClaim(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    /**
     * 角色字段最终需要 Integer，统一处理 Number 和字符串两种反序列化结果。
     */
    private Integer readIntegerClaim(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /**
     * 将 JWT 标准 Date 转为项目常用的 LocalDateTime，便于业务层计算和展示。
     */
    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }
}
