package com.john.campus.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.service.DownloadService;
import com.john.campus.vo.DownloadTicketVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 下载入口安全边界测试，确保限流身份只使用容器解析后的客户端地址。
 */
@ExtendWith(MockitoExtension.class)
class DownloadControllerTest {

    @Mock
    private DownloadService downloadService;

    @Test
    void createDownloadRecordShouldIgnoreClientSuppliedForwardingHeaders() {
        DownloadController controller = new DownloadController(downloadService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10");
        request.addHeader("Proxy-Client-IP", "203.0.113.11");
        request.addHeader("WL-Proxy-Client-IP", "203.0.113.12");
        request.addHeader("User-Agent", "test-agent");
        when(downloadService.createDownloadRecord(1L, "198.51.100.20", "test-agent"))
                .thenReturn(new DownloadTicketVO(1L, "ticket", 1L, 2L, "/download", 60L, true));

        controller.createDownloadRecord(1L, request);

        // 伪造代理头不能改变传给限流与审计服务的客户端地址。
        verify(downloadService).createDownloadRecord(1L, "198.51.100.20", "test-agent");
    }
}
