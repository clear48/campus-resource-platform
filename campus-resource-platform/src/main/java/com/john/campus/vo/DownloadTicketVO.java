package com.john.campus.vo;

/**
 * 创建下载记录接口响应，只返回前端需要的下载凭证信息，不暴露内部存储路径和数据库完整字段。
 *
 * @param downloadRecordId 下载记录 ID，后续请求文件流时作为路径参数
 * @param downloadTicket   仅可使用一次的随机下载票据，通过请求头提交而不进入 URL 和访问日志
 * @param resourceId       被下载资料 ID
 * @param fileId           被下载文件 ID
 * @param downloadUrl      文件流下载地址，指向 /api/v1/download-records/{id}/file
 * @param expireSeconds    下载地址有效期（秒），首版暂不实现过期机制，预留字段
 * @param counted          本次下载是否计入了下载量统计（去重期内重复下载不计入）
 */
public record DownloadTicketVO(
        Long downloadRecordId,
        String downloadTicket,
        Long resourceId,
        Long fileId,
        String downloadUrl,
        Long expireSeconds,
        boolean counted) {
}
