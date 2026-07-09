package com.john.campus.common;

/**
 * Redis Key 统一入口，避免业务代码手写 Key 导致命名不一致。
 */
public final class RedisKeyConstants {

    /**
     * Token 黑名单 Key，jti 来自 JWT，用于退出登录后让单个 Token 立即失效。
     */
    public static final String TOKEN_BLACKLIST = "crp:auth:token:blacklist:%s";

    /**
     * 文件 MD5 去重缓存 Key，值为 file_info.id，用于加速秒传判断。
     */
    public static final String FILE_MD5_CACHE = "crp:cache:file:md5:%s:%d";

    /**
     * 热门搜索词排行榜 Key，使用 ZSet 记录归一化关键词和搜索次数。
     */
    public static final String SEARCH_KEYWORD_RANK = "crp:rank:search:keyword:%s";

    /**
     * 按用户下载限流 Key，ZSet 滑动窗口，member 为请求唯一标识，score 为毫秒时间戳。
     */
    public static final String DOWNLOAD_RATE_USER = "crp:rate:download:user:%d";

    /**
     * 按 IP 下载限流 Key，ZSet 滑动窗口，用于限制单 IP 下载频率。
     */
    public static final String DOWNLOAD_RATE_IP = "crp:rate:download:ip:%s";

    /**
     * 同用户同资料重复下载去重 Key，String + TTL，命中期内不重复计入下载量和热度。
     */
    public static final String DOWNLOAD_DEDUP = "crp:dedup:download:%d:%d";

    /**
     * 下载量临时增量统计 Key，Hash 结构，field 为 resourceId，value 为待同步 MySQL 的下载增量。
     * 不主动设置 TTL，由后续定时任务同步到 MySQL 后主动 HDEL，避免统计丢失。
     */
    public static final String DOWNLOAD_DELTA = "crp:stats:resource:download:delta";

    private RedisKeyConstants() {
    }

    /**
     * 生成 Token 黑名单完整 Key，统一封装格式化逻辑。
     */
    public static String tokenBlacklist(String jti) {
        return String.format(TOKEN_BLACKLIST, jti);
    }

    /**
     * 生成文件 MD5 去重缓存完整 Key，统一封装格式化逻辑。
     */
    public static String fileMd5Cache(String fileMd5, long fileSize) {
        return String.format(FILE_MD5_CACHE, fileMd5, fileSize);
    }

    /**
     * 生成热门搜索词排行榜完整 Key，period 取 daily、weekly、monthly 等统计周期。
     */
    public static String searchKeywordRank(String period) {
        return String.format(SEARCH_KEYWORD_RANK, period);
    }

    /**
     * 生成按用户下载限流完整 Key，统一封装格式化逻辑。
     */
    public static String downloadRateUser(long userId) {
        return String.format(DOWNLOAD_RATE_USER, userId);
    }

    /**
     * 生成按 IP 下载限流完整 Key，统一封装格式化逻辑。
     */
    public static String downloadRateIp(String ip) {
        return String.format(DOWNLOAD_RATE_IP, ip);
    }

    /**
     * 生成同用户同资料重复下载去重完整 Key，统一封装格式化逻辑。
     */
    public static String downloadDedup(long userId, long resourceId) {
        return String.format(DOWNLOAD_DEDUP, userId, resourceId);
    }
}
