package com.john.campus.service;

/** 在线文件巡检与受控单文件恢复入口。 */
public interface FileIntegrityService {

    /** 分批巡检全部正常本地文件；批量异常熔断，单文件缺失二次确认后在线恢复。 */
    void scanAndRecover();
}
