package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.vo.DownloadTicketVO;
import com.john.campus.vo.MyDownloadRecordVO;

/**
 * 下载业务接口，负责下载权限校验、限流、去重计数、下载记录写入和我的下载记录查询。
 */
public interface DownloadService {

    /**
     * 创建下载记录并返回下载凭证，包含下载地址和是否计入统计。
     * 调用链：取当前用户 → 限流 → 校验资料 APPROVED → 校验文件正常 → 写下载记录 → 去重计数。
     *
     * @param resourceId 资料 ID，路径参数，必须为正整数
     * @param ip         客户端 IP，来自请求上下文，用于限流与审计
     * @param userAgent  客户端 User-Agent，可为空，用于审计
     * @return 下载凭证，含 downloadRecordId、downloadUrl 和本次是否计入下载量
     */
    DownloadTicketVO createDownloadRecord(Long resourceId, String ip, String userAgent);

    /**
     * 分页查询当前登录用户的下载记录，不接受前端传入 userId，避免越权。
     *
     * @param pageQuery 分页参数，pageNo 默认 1，pageSize 默认 10 最大 100
     * @return 只包含当前用户下载记录的分页结果
     */
    PageResult<MyDownloadRecordVO> listMyDownloadRecords(PageQuery pageQuery);
}
