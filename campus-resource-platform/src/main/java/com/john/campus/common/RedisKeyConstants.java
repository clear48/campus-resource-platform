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
}
