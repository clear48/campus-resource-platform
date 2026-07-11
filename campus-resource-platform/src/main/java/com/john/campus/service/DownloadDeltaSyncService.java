package com.john.campus.service;

/**
 * 下载量增量同步入口：把 Redis 中已去重的下载增量安全落库，避免下载请求高频直接更新 MySQL。
 */
public interface DownloadDeltaSyncService {

    /**
     * 执行一次下载增量同步；无数据、未获得锁或 Redis 不可用时安全结束，不向定时任务抛出异常。
     */
    void syncDownloadDeltas();
}
