package com.john.campus.service;

/**
 * 下载限流服务，负责在下载主流程进入数据库和文件 IO 前拦截高频请求。
 */
public interface DownloadRateLimiter {

    /**
     * 同时检查用户维度和 IP 维度下载频率，任一维度超限都拒绝本次下载。
     */
    void checkDownloadLimit(long userId, String ip);

    /**
     * 检查单个用户的下载频率，保护账号维度的下载量统计。
     */
    void checkUserLimit(long userId);

    /**
     * 检查单个 IP 的下载频率，降低同一出口或恶意脚本刷下载量的风险。
     */
    void checkIpLimit(String ip);
}
