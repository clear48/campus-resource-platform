package com.john.campus.service;

/** 在线单文件副本任务入口。 */
public interface FileBackupService {

    /** 分批扫描尚无READY副本的正常本地文件，并在每文件锁内创建可恢复副本。 */
    void backupPendingFiles();
}
