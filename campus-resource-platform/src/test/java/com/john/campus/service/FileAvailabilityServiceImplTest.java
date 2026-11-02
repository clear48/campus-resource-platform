package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.ErrorCode;
import com.john.campus.entity.FileInfo;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.FileAvailabilityServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文件可用性门禁必须兼容默认关闭模式，并在保护开启时对异常文件失败关闭。
 */
@ExtendWith(MockitoExtension.class)
class FileAvailabilityServiceImplTest {

    @Mock
    private FileProtectionStorageService fileProtectionStorageService;

    @Test
    void disabledProtectionShouldKeepExistingBusinessFlow() {
        FileAvailabilityService service = new FileAvailabilityServiceImpl(fileProtectionStorageService, false);
        FileInfo fileInfo = fileInfo();

        assertThatCode(() -> service.requireAvailable(fileInfo)).doesNotThrowAnyException();

        verify(fileProtectionStorageService, never()).inspect(fileInfo);
    }

    @Test
    void healthyFileShouldPassWhenProtectionEnabled() {
        FileAvailabilityService service = new FileAvailabilityServiceImpl(fileProtectionStorageService, true);
        FileInfo fileInfo = fileInfo();
        when(fileProtectionStorageService.inspect(fileInfo))
                .thenReturn(FileProtectionStorageService.FileState.HEALTHY);

        assertThatCode(() -> service.requireAvailable(fileInfo)).doesNotThrowAnyException();
    }

    @Test
    void missingFileShouldReturnStableTemporaryUnavailableCode() {
        FileAvailabilityService service = new FileAvailabilityServiceImpl(fileProtectionStorageService, true);
        FileInfo fileInfo = fileInfo();
        when(fileProtectionStorageService.inspect(fileInfo))
                .thenReturn(FileProtectionStorageService.FileState.MISSING);

        assertThatThrownBy(() -> service.requireAvailable(fileInfo))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FILE_TEMPORARILY_UNAVAILABLE.getCode());
    }

    private FileInfo fileInfo() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(200L);
        return fileInfo;
    }
}
