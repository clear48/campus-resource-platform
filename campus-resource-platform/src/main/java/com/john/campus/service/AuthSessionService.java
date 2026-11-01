package com.john.campus.service;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** 刷新会话存储：仅存凭据摘要，所有状态变化由同一 Redis Key 的 Lua 原子完成。 */
@Service
public class AuthSessionService {
    public static final long SESSION_SECONDS = 7 * 24 * 60 * 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> CREATE = script("auth-session-create.lua");
    private static final DefaultRedisScript<Long> ROTATE = script("auth-session-rotate.lua");
    private static final DefaultRedisScript<Long> REVOKE = script("auth-session-revoke.lua");
    private final StringRedisTemplate redis;

    public AuthSessionService(StringRedisTemplate redis) { this.redis = redis; }

    /** 本次签发所需的内存凭据，明文仅返回给客户端，不写 Redis 或日志。 */
    public record Credential(String sid, String token, String hash) { }
    public record Session(Long userId, long remainingSeconds) { }

    public Credential newCredential(String sid) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String actualSid = sid == null ? randomSid() : sid;
        String token = actualSid + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new Credential(actualSid, token, digest(token));
    }

    /** 创建会话和设置过期时间必须原子执行，避免无 TTL 的刷新凭据。 */
    public void create(Credential credential, Long userId) {
        Long result = redis.execute(CREATE, List.of(key(credential)), userId.toString(), credential.hash(),
                Long.toString(SESSION_SECONDS));
        if (!Long.valueOf(1).equals(result)) { throw new IllegalStateException("Cannot create auth session"); }
    }

    /** 校验凭据后再读用户；已使用的凭据重放会撤销整个会话，随机猜测不会。 */
    public Session inspect(Credential credential) {
        Long state = redis.execute(ROTATE, List.of(key(credential)), credential.hash(), "", "inspect");
        if (!Long.valueOf(1).equals(state)) { throw invalid(); }
        Object userId = redis.opsForHash().get(key(credential), "userId");
        Long ttl = redis.getExpire(key(credential));
        if (userId == null || ttl == null || ttl <= 0) { throw invalid(); }
        return new Session(Long.valueOf(userId.toString()), ttl);
    }

    /** CAS 不重新创建已退出的会话，也不延长固定七天的绝对期限。 */
    public long rotate(Credential oldCredential, Credential next) {
        Long ttl = redis.execute(ROTATE, List.of(key(oldCredential)), oldCredential.hash(), next.hash(), "rotate");
        if (ttl == null || ttl <= 0) { throw invalid(); }
        return ttl;
    }

    /** 已轮换的已知凭据仍可退出，覆盖刷新与退出交错的情况。 */
    public void revoke(Credential credential) {
        Long result = redis.execute(REVOKE, List.of(key(credential)), credential.hash());
        if (result == null) { throw new IllegalStateException("Cannot revoke auth session"); }
        if (result < 0) { throw invalid(); }
    }

    public void revokeTrustedSession(String sid) { redis.delete(RedisKeyConstants.authSession(sid)); }

    public Credential parse(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}")) { throw invalid(); }
        String sid = token.substring(0, 32);
        return new Credential(sid, token, digest(token));
    }

    private String key(Credential credential) { return RedisKeyConstants.authSession(credential.sid()); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID); }
    private static String randomSid() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static DefaultRedisScript<Long> script(String name) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/" + name));
        script.setResultType(Long.class);
        return script;
    }
}
