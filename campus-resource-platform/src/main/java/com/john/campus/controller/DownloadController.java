package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.service.DownloadService;
import com.john.campus.service.DownloadService.DownloadFileInfo;
import com.john.campus.vo.DownloadTicketVO;
import com.john.campus.vo.MyDownloadRecordVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下载接口入口，负责接收 HTTP 请求并把业务编排交给 DownloadService。
 * 所有接口均需登录，路径不在 WebMvcConfig 放行列表中。
 */
@RestController
@RequestMapping("/api/v1")
public class DownloadController {

    public static final String DOWNLOAD_TICKET_HEADER = "X-Download-Ticket";

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * 创建下载记录并获取下载地址：登录态由 JWT 拦截器校验，客户端 IP 和 UA 从请求上下文提取。
     */
    @PostMapping("/resources/{resourceId}/download-records")
    public ApiResponse<DownloadTicketVO> createDownloadRecord(
            @PathVariable Long resourceId,
            HttpServletRequest request) {
        // Tomcat 只会为可信内网代理解析转发头；业务层统一读取解析后的 remoteAddr，不能再直接信任客户端请求头。
        String ip = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.success(downloadService.createDownloadRecord(resourceId, ip, userAgent));
    }

    /**
     * 下载文件流：校验下载记录归属后，以二进制流返回物理文件。
     * 返回 ResponseEntity 而非 ApiResponse，因为文件流属于二进制内容，不符合 JSON 包装协议。
     */
    @GetMapping("/download-records/{downloadRecordId}/file")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable Long downloadRecordId,
            @RequestHeader(DOWNLOAD_TICKET_HEADER) String downloadTicket) {
        DownloadFileInfo fileInfo = downloadService.loadFile(downloadRecordId, downloadTicket);

        HttpHeaders headers = new HttpHeaders();
        // 历史 MIME 可能来自旧客户端，普通下载固定为二进制附件，阻止浏览器主动解释内容。
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        String originalName = fileInfo.originalName() == null || fileInfo.originalName().isBlank()
                ? "download-" + downloadRecordId
                : fileInfo.originalName();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(originalName, StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(fileInfo.contentLength());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");

        return new ResponseEntity<>(
                new InputStreamResource(fileInfo.inputStream()),
                headers,
                HttpStatus.OK);
    }

    /**
     * 查询我的下载记录：只按当前登录用户查询，不接受前端传入 userId。
     */
    @GetMapping("/users/me/download-records")
    public ApiResponse<PageResult<MyDownloadRecordVO>> listMyDownloadRecords(
            @Valid PageQuery pageQuery) {
        return ApiResponse.success(downloadService.listMyDownloadRecords(pageQuery));
    }

}
