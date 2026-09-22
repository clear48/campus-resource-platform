package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.john.campus.service.impl.HealthServiceImpl;
import com.john.campus.vo.ReadinessVO;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 就绪检查服务测试，覆盖关键依赖成功、异常和磁盘空间不足分支。
 */
@ExtendWith(MockitoExtension.class)
class HealthServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    DataSource dataSource;
    @Mock
    Connection connection;
    @Mock
    RedisConnectionFactory redisConnectionFactory;
    @Mock
    RedisConnection redisConnection;

    @Test
    void livenessShouldRemainUpWithoutProbingDependencies() {
        HealthService service = new HealthServiceImpl(
                dataSource, redisConnectionFactory, tempDir.toString(), 1L);

        assertThat(service.check().status()).isEqualTo("UP");
    }

    @Test
    void allDependenciesAvailableShouldBeReady() throws Exception {
        HealthService service = readyService(1L);

        ReadinessVO result = service.checkReadiness();

        assertThat(result.ready()).isTrue();
        assertThat(result.status()).isEqualTo("UP");
        assertThat(result.components().mysql()).isEqualTo("UP");
        assertThat(result.components().redis()).isEqualTo("UP");
        assertThat(result.components().storage()).isEqualTo("UP");
    }

    @Test
    void mysqlExceptionShouldReturnDownWithoutThrowing() throws Exception {
        when(dataSource.getConnection()).thenThrow(new IllegalStateException("sensitive database detail"));
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        HealthService service = new HealthServiceImpl(
                dataSource, redisConnectionFactory, tempDir.toString(), 1L);

        ReadinessVO result = service.checkReadiness();

        assertThat(result.ready()).isFalse();
        assertThat(result.components().mysql()).isEqualTo("DOWN");
        assertThat(result.toString()).doesNotContain("sensitive database detail");
    }

    @Test
    void redisFailureShouldReturnDown() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenThrow(new IllegalStateException("sensitive redis detail"));
        HealthService service = new HealthServiceImpl(
                dataSource, redisConnectionFactory, tempDir.toString(), 1L);

        ReadinessVO result = service.checkReadiness();

        assertThat(result.ready()).isFalse();
        assertThat(result.components().redis()).isEqualTo("DOWN");
        assertThat(result.toString()).doesNotContain("sensitive redis detail");
    }

    @Test
    void insufficientStorageSpaceShouldReturnDown() throws Exception {
        HealthService service = readyService(Long.MAX_VALUE);

        ReadinessVO result = service.checkReadiness();

        assertThat(result.ready()).isFalse();
        assertThat(result.components().storage()).isEqualTo("DOWN");
    }

    private HealthService readyService(long minFreeSpaceBytes) throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");
        return new HealthServiceImpl(
                dataSource, redisConnectionFactory, tempDir.toString(), minFreeSpaceBytes);
    }
}
