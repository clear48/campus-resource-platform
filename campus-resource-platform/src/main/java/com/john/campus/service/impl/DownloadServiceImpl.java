package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.PageResult;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.PageQuery;
import com.john.campus.entity.DownloadRecord;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.DownloadRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.DownloadRateLimiter;
import com.john.campus.service.DownloadService;
import com.john.campus.service.FileStorageService;
import com.john.campus.service.RankingService;
import com.john.campus.vo.DownloadTicketVO;
import com.john.campus.vo.MyDownloadRecordVO;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 下载业务实现：编排限流、状态校验、记录写入、去重计数和我的下载记录查询。
 * Redis 操作不纳入数据库事务，下载量走「先写 Redis 增量、后续定时任务同步 MySQL」的最终一致方案。
 */
@Service
public class DownloadServiceImpl implements DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadServiceImpl.class);

    /**
     * 同用户同资料重复下载去重 TTL，10 分钟内同一用户下载同一资料不重复计入下载量和热度。
     */
    private static final Duration DEDUP_TTL = Duration.ofMinutes(10);
    /**
     * 票据只覆盖前端完成第二步文件请求所需的短窗口，过期后必须重新经过下载限流。
     */
    private static final Duration DOWNLOAD_TICKET_TTL = Duration.ofSeconds(60);
    private static final int DOWNLOAD_TICKET_RANDOM_BYTES = 32;
    private static final String DOWNLOAD_TICKET_PATTERN = "^[A-Za-z0-9_-]{43}$";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * 原子消费一次性票据，保证同一票据在并发请求中最多只有一个请求成功。
     */
    private static final DefaultRedisScript<Long> CONSUME_DOWNLOAD_TICKET_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return 0
                    end
                    redis.call('DEL', KEYS[1])
                    return 1
                    """, Long.class);
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DOWNLOAD_FILE_PATH_FORMAT = "/api/v1/download-records/%d/file";

    private final DownloadRecordMapper downloadRecordMapper;
    private final ResourceMapper resourceMapper;
    private final FileInfoMapper fileInfoMapper;
    private final DownloadRateLimiter downloadRateLimiter;
    private final FileStorageService fileStorageService;
    private final StringRedisTemplate stringRedisTemplate;
    /**
     * 排行榜只承接下载成功后的派生热度更新，任何 Redis 故障均不能改变下载记录的写入结果。
     */
    private final RankingService rankingService;

    public DownloadServiceImpl(
            DownloadRecordMapper downloadRecordMapper,
            ResourceMapper resourceMapper,
            FileInfoMapper fileInfoMapper,
            DownloadRateLimiter downloadRateLimiter,
            FileStorageService fileStorageService,
            StringRedisTemplate stringRedisTemplate,
            RankingService rankingService) {
        this.downloadRecordMapper = downloadRecordMapper;
        this.resourceMapper = resourceMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.downloadRateLimiter = downloadRateLimiter;
        this.fileStorageService = fileStorageService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.rankingService = rankingService;
    }

    /**
     * 创建下载记录主流程：限流 → 校验资料和文件状态 → 写入下载流水 → 去重计数。
     * 下载量增量写 Redis Hash，不在本事务内直接 UPDATE resource.download_count。
     */
    @Override
    public DownloadTicketVO createDownloadRecord(Long resourceId, String ip, String userAgent) {
        Long userId = UserContextHolder.getRequiredUserId();

        // 限流检查前置，避免无效请求穿透到数据库。
        downloadRateLimiter.checkDownloadLimit(userId, ip);

        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料 ID 不合法");
        }

        // 校验资料存在且可通过审核状态。
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        if (!resource.isApproved()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料未审核通过或已下架，不可下载");
        }

        // 校验物理文件存在且正常，防止下载记录指向已删除或缺失的文件。
        FileInfo fileInfo = fileInfoMapper.selectNormalById(resource.getFileId());
        if (fileInfo == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已删除");
        }

        // 写入下载成功记录，审计字段（IP、UA）一并落库。
        DownloadRecord record = new DownloadRecord();
        record.setUserId(userId);
        record.setResourceId(resourceId);
        record.setFileId(fileInfo.getId());
        record.setUserIp(ip);
        record.setUserAgent(userAgent);
        record.setDownloadStatus(DownloadRecord.STATUS_SUCCESS);
        downloadRecordMapper.insert(record);

        // 数据库记录只负责审计；真正的文件访问权由短期、随机、一次性的 Redis 票据承担。
        String downloadTicket = createDownloadTicket(userId, record.getId());

        // 去重判断：去重 Key 命中期内不重复计入下载量，但允许下载本身。
        boolean counted = tryCountDownload(userId, resourceId);
        if (counted) {
            // 只有 SETNX 去重和 Hash 增量都成功后才记热度，避免重复下载把排行分数放大。
            recordDownloadHeatSafely(resourceId);
        }

        String downloadUrl = String.format(DOWNLOAD_FILE_PATH_FORMAT, record.getId());
        return new DownloadTicketVO(
                record.getId(),
                downloadTicket,
                resourceId,
                fileInfo.getId(),
                downloadUrl,
                DOWNLOAD_TICKET_TTL.toSeconds(),
                counted);
    }

    /**
     * 我的下载记录只按当前登录用户查询，不信任前端传入 userId。
     */
    @Override
    public PageResult<MyDownloadRecordVO> listMyDownloadRecords(PageQuery pageQuery) {
        Long userId = UserContextHolder.getRequiredUserId();
        int pageNo = resolvePageNo(pageQuery);
        int pageSize = resolvePageSize(pageQuery);
        int offset = (pageNo - 1) * pageSize;

        List<DownloadRecord> records = downloadRecordMapper.selectByUser(userId, offset, pageSize);
        long total = downloadRecordMapper.countByUser(userId);

        // 批量查询资料标题，避免列表展示只有 ID 没有可读名称。
        Map<Long, Resource> resourceMap = records.stream()
                .map(DownloadRecord::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .map(resourceMapper::selectById)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Resource::getId, r -> r, (existing, replacement) -> existing));

        List<MyDownloadRecordVO> voList = records.stream()
                .map(r -> toMyDownloadRecordVO(r, resourceMap.get(r.getResourceId())))
                .toList();

        return PageResult.of(voList, pageNo, pageSize, total);
    }

    /**
     * 读取下载文件流：校验下载记录存在且归属合法 → 定位物理文件 → 委托 FileStorageService 读流。
     * 不在事务中执行文件 IO，避免事务持有数据库连接等待磁盘读取。
     */
    @Override
    public DownloadFileInfo loadFile(Long downloadRecordId, String downloadTicket) {
        if (downloadRecordId == null || downloadRecordId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "下载记录 ID 不合法");
        }
        if (downloadTicket == null || !downloadTicket.matches(DOWNLOAD_TICKET_PATTERN)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "下载票据格式不合法");
        }

        LoginUser currentUser = UserContextHolder.getRequired();
        consumeDownloadTicket(currentUser.userId(), downloadRecordId, downloadTicket);

        DownloadRecord record = downloadRecordMapper.selectById(downloadRecordId);
        if (record == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "下载记录不存在");
        }

        if (!currentUser.userId().equals(record.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该下载记录");
        }

        // 票据签发后资料仍可能被管理员下架，真正取流前必须以 MySQL 当前状态为准。
        Resource resource = resourceMapper.selectById(record.getResourceId());
        if (resource == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        if (!resource.isApproved() || !Objects.equals(resource.getFileId(), record.getFileId())) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料已下架或文件已变更，不能继续下载");
        }

        FileInfo fileInfo = fileInfoMapper.selectNormalById(record.getFileId());
        if (fileInfo == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已删除");
        }

        FileStorageService.FileResource fileResource = fileStorageService.loadAsResource(fileInfo.getStoragePath());
        return new DownloadFileInfo(
                fileResource.inputStream(),
                fileInfo.getOriginalName(),
                fileInfo.getMimeType(),
                fileResource.contentLength());
    }

    /**
     * 签发随机票据时只把 SHA-256 摘要写入 Redis；Redis 故障必须失败关闭，不能回退到长期记录 ID。
     */
    private String createDownloadTicket(Long userId, Long downloadRecordId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] randomBytes = new byte[DOWNLOAD_TICKET_RANDOM_BYTES];
            SECURE_RANDOM.nextBytes(randomBytes);
            String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            String ticketKey = RedisKeyConstants.downloadTicket(userId, downloadRecordId, sha256(ticket));
            try {
                Boolean created = stringRedisTemplate.opsForValue()
                        .setIfAbsent(ticketKey, "1", DOWNLOAD_TICKET_TTL);
                if (Boolean.TRUE.equals(created)) {
                    return ticket;
                }
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.SERVER_ERROR, "下载票据服务暂不可用");
            }
        }
        throw new BusinessException(ErrorCode.SERVER_ERROR, "下载票据生成失败");
    }

    /**
     * 先按当前用户、记录和票据摘要定位 Key，再通过 Lua 原子删除；无效、过期和重放统一拒绝。
     */
    private void consumeDownloadTicket(Long userId, Long downloadRecordId, String downloadTicket) {
        String ticketKey = RedisKeyConstants.downloadTicket(userId, downloadRecordId, sha256(downloadTicket));
        try {
            Long consumed = stringRedisTemplate.execute(
                    CONSUME_DOWNLOAD_TICKET_SCRIPT,
                    List.of(ticketKey));
            if (!Long.valueOf(1L).equals(consumed)) {
                throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "下载票据无效、已过期或已使用");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "下载票据服务暂不可用");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 Java 标准算法；若运行时缺失，属于不可恢复的环境错误。
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * 去重 + 下载量增量：SETNX 写去重 Key → 新下载则 HINCRBY 下载量增量 Hash。
     * Redis 异常时不阻断下载主流程，只记录日志并跳过计数，避免 Redis 故障放大为下载不可用。
     *
     * @return true 表示本次下载被计入下载量统计，false 表示去重期内重复下载或 Redis 异常跳过
     */
    private boolean tryCountDownload(Long userId, Long resourceId) {
        String dedupKey = RedisKeyConstants.downloadDedup(userId, resourceId);
        try {
            Boolean firstTime = stringRedisTemplate.opsForValue()
                    .setIfAbsent(dedupKey, "1", DEDUP_TTL.toSeconds(), TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(firstTime)) {
                stringRedisTemplate.opsForHash()
                        .increment(RedisKeyConstants.DOWNLOAD_DELTA, String.valueOf(resourceId), 1);
                return true;
            }
            return false;
        } catch (RuntimeException ex) {
            log.warn("下载去重或增量统计失败，跳过计数: userId={}, resourceId={}", userId, resourceId, ex);
            return false;
        }
    }

    /**
     * 热度属于下载主业务提交后的可降级副作用；即使排行榜服务意外抛错，也不能让已创建的下载凭证失败。
     */
    private void recordDownloadHeatSafely(Long resourceId) {
        try {
            rankingService.recordResourceDownload(resourceId);
        } catch (RuntimeException ex) {
            log.warn("记录下载热度失败，下载主流程已成功: resourceId={}", resourceId, ex);
        }
    }

    /**
     * 将下载记录实体和资料信息合并为列表项 VO，不暴露 IP、UA 等审计字段。
     */
    private MyDownloadRecordVO toMyDownloadRecordVO(DownloadRecord record, Resource resource) {
        return new MyDownloadRecordVO(
                record.getId(),
                record.getResourceId(),
                resource != null ? resource.getTitle() : null,
                record.getFileId(),
                record.getDownloadStatus(),
                record.getCreatedAt());
    }

    private int resolvePageNo(PageQuery pageQuery) {
        Integer pageNo = pageQuery == null ? DEFAULT_PAGE_NO : pageQuery.getPageNo();
        if (pageNo == null) {
            return DEFAULT_PAGE_NO;
        }
        if (pageNo < DEFAULT_PAGE_NO) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageNo 必须大于等于 1");
        }
        return pageNo;
    }

    private int resolvePageSize(PageQuery pageQuery) {
        Integer pageSize = pageQuery == null ? DEFAULT_PAGE_SIZE : pageQuery.getPageSize();
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageSize 必须在 1 到 100 之间");
        }
        return pageSize;
    }
}
