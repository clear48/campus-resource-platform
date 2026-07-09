package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.vo.DownloadTicketVO;
import com.john.campus.vo.MyDownloadRecordVO;
import java.io.InputStream;

/**
 * 下载业务接口，负责下载权限校验、限流、去重计数、下载记录写入、文件流读取和我的下载记录查询。
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
     * 按下载记录 ID 读取文件流，校验记录归属后定位物理文件并返回输入流及元数据。
     * 调用链：查下载记录 → 校验归属（本人或管理员）→ 查 file_info → FileStorageService 读流。
     *
     * @param downloadRecordId 下载记录 ID，路径参数，必须为正整数
     * @return 文件输入流及响应头所需元数据
     */
    DownloadFileInfo loadFile(Long downloadRecordId);

    /**
     * 分页查询当前登录用户的下载记录，不接受前端传入 userId，避免越权。
     *
     * @param pageQuery 分页参数，pageNo 默认 1，pageSize 默认 10 最大 100
     * @return 只包含当前用户下载记录的分页结果
     */
    PageResult<MyDownloadRecordVO> listMyDownloadRecords(PageQuery pageQuery);

    /**
     * 下载文件流方法的返回结果，封装 Controller 构建二进制响应所需的全部数据。
     *
     * @param inputStream   文件输入流，调用方负责在使用后关闭
     * @param originalName  原始文件名，用于 Content-Disposition 下载提示
     * @param mimeType      文件 MIME 类型，用于 Content-Type
     * @param contentLength 文件字节数，用于 Content-Length
     */
    record DownloadFileInfo(InputStream inputStream, String originalName, String mimeType, long contentLength) {
    }
}
