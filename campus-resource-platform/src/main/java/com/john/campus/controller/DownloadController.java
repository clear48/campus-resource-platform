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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下载接口入口，负责接收 HTTP 请求并把业务编排交给 DownloadService。
 * 所有接口均需登录，路径不在 WebMvcConfig 放行列表中。
 */
@RestController
@RequestMapping("/api/v1")
public class DownloadController {

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
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.success(downloadService.createDownloadRecord(resourceId, ip, userAgent));
    }

    /**
     * 下载文件流：校验下载记录归属后，以二进制流返回物理文件。
     * 返回 ResponseEntity 而非 ApiResponse，因为文件流属于二进制内容，不符合 JSON 包装协议。
     */
    @GetMapping("/download-records/{downloadRecordId}/file")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable Long downloadRecordId) {
        DownloadFileInfo fileInfo = downloadService.loadFile(downloadRecordId);

        String contentType = StringUtils.hasText(fileInfo.mimeType())
                ? fileInfo.mimeType()
                : "application/octet-stream";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(contentType));
        // RFC 5987 编码，兼容中文文件名：filename 为 ASCII 兜底，filename* 为 UTF-8 编码。
        String encodedName = URLEncoder.encode(fileInfo.originalName(), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        headers.setContentLength(fileInfo.contentLength());

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

    /**
     * 从请求中提取客户端真实 IP，优先读取反向代理转发的 X-Forwarded-For 头。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多级代理时 X-Forwarded-For 可能为逗号分隔列表，取第一个原始客户端 IP。
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
