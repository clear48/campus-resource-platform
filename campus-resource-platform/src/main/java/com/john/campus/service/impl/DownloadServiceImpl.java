package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
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
import com.john.campus.vo.DownloadTicketVO;
import com.john.campus.vo.MyDownloadRecordVO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DOWNLOAD_FILE_PATH_FORMAT = "/api/v1/download-records/%d/file";

    private final DownloadRecordMapper downloadRecordMapper;
    private final ResourceMapper resourceMapper;
    private final FileInfoMapper fileInfoMapper;
    private final DownloadRateLimiter downloadRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;

    public DownloadServiceImpl(
            DownloadRecordMapper downloadRecordMapper,
            ResourceMapper resourceMapper,
            FileInfoMapper fileInfoMapper,
            DownloadRateLimiter downloadRateLimiter,
            StringRedisTemplate stringRedisTemplate) {
        this.downloadRecordMapper = downloadRecordMapper;
        this.resourceMapper = resourceMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.downloadRateLimiter = downloadRateLimiter;
        this.stringRedisTemplate = stringRedisTemplate;
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

        // 去重判断：去重 Key 命中期内不重复计入下载量，但允许下载本身。
        boolean counted = tryCountDownload(userId, resourceId);

        String downloadUrl = String.format(DOWNLOAD_FILE_PATH_FORMAT, record.getId());
        return new DownloadTicketVO(
                record.getId(),
                resourceId,
                fileInfo.getId(),
                downloadUrl,
                null,
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
