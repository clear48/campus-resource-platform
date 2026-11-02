package com.john.campus.service;

import com.john.campus.entity.FileInfo;

/**
 * 业务入口的文件可用性门禁，避免把正在自动恢复的文件继续用于秒传、下载或审核。
 */
public interface FileAvailabilityService {

    /**
     * 功能关闭时保持原有行为；功能开启时，只有活动文件健康才允许业务继续。
     */
    void requireAvailable(FileInfo fileInfo);
}
