package com.john.campus.service.impl;

import com.john.campus.service.HealthService;
import com.john.campus.vo.HealthVO;
import com.john.campus.vo.ReadinessComponentsVO;
import com.john.campus.vo.ReadinessVO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.LocalDateTime;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

/**
 * 健康检查服务实现，返回应用名、状态和检查时间。
 */
@Service
public class HealthServiceImpl implements HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthServiceImpl.class);
    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Path uploadStorageRoot;
    private final long uploadMinFreeSpaceBytes;

    public HealthServiceImpl(
            DataSource dataSource,
            RedisConnectionFactory redisConnectionFactory,
            @Value("${app.upload.storage-path}") String uploadStoragePath,
            @Value("${app.upload.min-free-space-bytes}") long uploadMinFreeSpaceBytes) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.uploadStorageRoot = Paths.get(uploadStoragePath).toAbsolutePath().normalize();
        this.uploadMinFreeSpaceBytes = uploadMinFreeSpaceBytes;
    }

    /**
     * 存活检查只确认应用进程能响应，保持原接口语义和兼容性。
     */
    @Override
    public HealthVO check() {
        return new HealthVO("campus-resource-platform", UP, LocalDateTime.now());
    }

    /**
     * 依次检查关键依赖并汇总状态。单个探针失败不抛出异常，避免健康接口泄露内部连接和路径信息。
     */
    @Override
    public ReadinessVO checkReadiness() {
        String mysql = checkMysql();
        String redis = checkRedis();
        String storage = checkUploadStorage();
        String status = UP.equals(mysql) && UP.equals(redis) && UP.equals(storage) ? UP : DOWN;
        return new ReadinessVO(
                "campus-resource-platform",
                status,
                new ReadinessComponentsVO(mysql, redis, storage),
                LocalDateTime.now());
    }

    private String checkMysql() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? UP : DOWN;
        } catch (Exception ex) {
            // 日志只记录异常类型，不记录可能包含连接串、账号或网络地址的异常消息。
            log.warn("MySQL readiness check failed: {}", ex.getClass().getSimpleName());
            return DOWN;
        }
    }

    private String checkRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? UP : DOWN;
        } catch (Exception ex) {
            log.warn("Redis readiness check failed: {}", ex.getClass().getSimpleName());
            return DOWN;
        }
    }

    private String checkUploadStorage() {
        try {
            Files.createDirectories(uploadStorageRoot);
            boolean writable = Files.isDirectory(uploadStorageRoot) && Files.isWritable(uploadStorageRoot);
            if (!writable) {
                return DOWN;
            }
            // 权限位只能提供静态线索；实际创建并删除探针文件，才能发现只读挂载和运行用户无写权限。
            Path writeProbe = Files.createTempFile(uploadStorageRoot, ".readiness-", ".tmp");
            Files.delete(writeProbe);
            long usableSpace = Files.getFileStore(uploadStorageRoot).getUsableSpace();
            return usableSpace >= uploadMinFreeSpaceBytes ? UP : DOWN;
        } catch (Exception ex) {
            log.warn("Upload storage readiness check failed: {}", ex.getClass().getSimpleName());
            return DOWN;
        }
    }
}
