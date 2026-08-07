package com.john.campus.service;

/**
 * 文件 MD5 缓存统一入口，封装正缓存、负缓存、坏值清理和 Redis 降级策略。
 */
public interface FileMd5CacheService {

    /**
     * 三态读取结果：命中正值、确认不存在、缓存无有效结论。
     */
    enum CacheState {
        FOUND,
        NOT_FOUND,
        ABSENT
    }

    /**
     * 缓存读取结果；只有 {@link CacheState#FOUND} 状态携带正数 fileId。
     */
    record LookupResult(CacheState state, Long fileId) {

        public static LookupResult found(long fileId) {
            return new LookupResult(CacheState.FOUND, fileId);
        }

        public static LookupResult notFound() {
            return new LookupResult(CacheState.NOT_FOUND, null);
        }

        public static LookupResult absent() {
            return new LookupResult(CacheState.ABSENT, null);
        }
    }

    /**
     * 读取 MD5 + 文件大小对应的三态缓存结果；Redis 异常时返回 ABSENT。
     */
    LookupResult get(String fileMd5, long fileSize);

    /**
     * 无条件写入正数 fileId，用于在数据库成功后覆盖可能存在的旧负缓存。
     */
    void putFound(String fileMd5, long fileSize, Long fileId);

    /**
     * 仅在 Key 不存在时写入负缓存，避免覆盖并发上传已经写入的正值。
     */
    void putNotFound(String fileMd5, long fileSize);

    /**
     * 最佳努力删除指定缓存，供后续文件生命周期变更入口显式失效。
     */
    void evict(String fileMd5, long fileSize);
}
