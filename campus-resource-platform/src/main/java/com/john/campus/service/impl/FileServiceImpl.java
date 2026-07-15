package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.FileInfo;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.service.FileAuthorizationService;
import com.john.campus.service.FileService;
import com.john.campus.service.FileStorageService;
import com.john.campus.vo.FileCheckVO;
import com.john.campus.vo.FileUploadVO;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传业务实现，编排校验、MD5 去重、秒传与落盘入库。
 * 关键约束：耗时文件 IO 一律放在数据库写操作之外，入库失败补偿删除已落盘文件。
 */
@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    /**
     * 允许上传的扩展名白名单，是文件类型的主要安全闸口。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "zip", "rar", "7z", "txt", "md", "jpg", "jpeg", "png");
    /**
     * 业务侧文件大小上限，与 multipart 配置的 50MB 对齐，作为二次防御。
     */
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;
    /**
     * 原始文件名与 MIME 长度上限，分别对齐列宽 VARCHAR(255)、VARCHAR(100)。
     */
    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;
    private static final int MAX_MIME_TYPE_LENGTH = 100;
    /**
     * MD5 预检参数格式：32 位十六进制。
     */
    private static final String MD5_PATTERN = "^[a-fA-F0-9]{32}$";
    /**
     * 文件 MD5 去重缓存 TTL，取设计文档建议区间下限，过期后回查数据库。
     */
    private static final Duration FILE_MD5_CACHE_TTL = Duration.ofHours(6);

    /**
     * 文件表访问入口。
     */
    private final FileInfoMapper fileInfoMapper;
    /**
     * 文件存储服务，负责 MD5 计算、落盘与删除补偿。
     */
    private final FileStorageService fileStorageService;
    /**
     * 文件元数据与当前用户授权的短事务服务。
     */
    private final FileAuthorizationService fileAuthorizationService;
    /**
     * Redis 用于文件 MD5 去重缓存，命中可跳过数据库查询。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public FileServiceImpl(
            FileInfoMapper fileInfoMapper,
            FileStorageService fileStorageService,
            StringRedisTemplate stringRedisTemplate,
            FileAuthorizationService fileAuthorizationService) {
        this.fileInfoMapper = fileInfoMapper;
        this.fileStorageService = fileStorageService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.fileAuthorizationService = fileAuthorizationService;
    }

    /**
     * 上传主流程：校验和文件 IO 在事务外完成，命中去重则秒传，否则落盘后入库。
     */
    @Override
    public FileUploadVO upload(MultipartFile file) {
        String fileExt = validateAndResolveExt(file);
        String fileMd5 = fileStorageService.calculateMd5(file);
        long fileSize = file.getSize();

        // 去重以数据库唯一索引为准（需完整记录组装 VO）；命中则秒传，不重复落盘。
        FileInfo existing = fileInfoMapper.selectByMd5AndSize(fileMd5, fileSize);
        if (existing != null) {
            return secondUpload(existing);
        }

        // 未命中：先落盘（耗时文件 IO，置于任何数据库写操作之外），再入库。
        FileStorageService.StoredFile stored = fileStorageService.store(file, fileExt);
        FileInfo fileInfo = buildFileInfo(file, fileMd5, fileSize, fileExt, stored);
        return persistOrFallback(fileInfo, stored, fileMd5, fileSize);
    }

    /**
     * MD5 预检，供前端上传前判断是否可秒传；先查缓存，未命中回查数据库并回填。
     */
    @Override
    public FileCheckVO checkByMd5AndSize(String fileMd5, Long fileSize) {
        if (!StringUtils.hasText(fileMd5) || !fileMd5.matches(MD5_PATTERN)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "fileMd5 格式不正确");
        }
        if (fileSize == null || fileSize < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "fileSize 不合法");
        }
        // 先查缓存加速秒传判断，命中直接返回，避免高频预检打到数据库。
        Long cachedFileId = getCachedFileId(fileMd5, fileSize);
        if (cachedFileId != null) {
            return authorizedCheckResult(cachedFileId);
        }
        FileInfo fileInfo = fileInfoMapper.selectByMd5AndSize(fileMd5, fileSize);
        if (fileInfo == null) {
            return new FileCheckVO(false, null);
        }
        cacheFileId(fileMd5, fileSize, fileInfo.getId());
        return authorizedCheckResult(fileInfo.getId());
    }

    /**
     * 插入新文件；并发命中唯一索引则转秒传，其他入库异常补偿删除已落盘文件。
     */
    private FileUploadVO persistOrFallback(
            FileInfo fileInfo, FileStorageService.StoredFile stored, String fileMd5, Long fileSize) {
        try {
            // 单条 INSERT 自身即原子写入，无需跨文件 IO 的长事务。
            fileAuthorizationService.createAuthorizedFile(fileInfo, fileInfo.getUploaderId());
            cacheFileId(fileInfo.getFileMd5(), fileInfo.getFileSize(), fileInfo.getId());
            return toUploadVO(fileInfo, false);
        } catch (DuplicateKeyException ex) {
            // 并发下别的请求已入库相同文件：删除本次多余落盘，转为秒传。
            fileStorageService.delete(stored.storagePath());
            FileInfo concurrent = fileInfoMapper.selectByMd5AndSize(fileMd5, fileSize);
            if (concurrent == null) {
                throw new BusinessException(ErrorCode.DATA_DUPLICATE, "文件已存在");
            }
            return secondUpload(concurrent);
        } catch (RuntimeException ex) {
            // 其他入库异常：补偿删除已落盘文件，避免产生孤儿文件。
            fileStorageService.delete(stored.storagePath());
            throw ex;
        }
    }

    /**
     * 秒传：命中既有文件时引用次数原子自增，并回填缓存，返回该文件。
     */
    private FileUploadVO secondUpload(FileInfo fileInfo) {
        // 只有真实文件上传并经服务端计算内容哈希后，才为当前用户建立授权。
        fileAuthorizationService.authorizeExistingFile(
                fileInfo.getId(), UserContextHolder.getRequiredUserId());
        cacheFileId(fileInfo.getFileMd5(), fileInfo.getFileSize(), fileInfo.getId());
        return toUploadVO(fileInfo, true);
    }

    /**
     * 全局 MD5 缓存只用于定位候选文件，最终是否返回 fileId 必须按当前用户查库授权。
     */
    private FileCheckVO authorizedCheckResult(Long fileId) {
        Long userId = UserContextHolder.getRequiredUserId();
        if (!fileAuthorizationService.isAuthorized(userId, fileId)) {
            return new FileCheckVO(false, null);
        }
        return new FileCheckVO(true, fileId);
    }

    /**
     * 上传前校验（非空、大小、扩展名白名单），并返回安全扩展名。
     */
    private String validateAndResolveExt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String fileExt = fileStorageService.resolveExtension(file.getOriginalFilename());
        // 空扩展名或不在白名单内均视为类型不允许，扩展名白名单为类型校验的准绳。
        if (!ALLOWED_EXTENSIONS.contains(fileExt)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        return fileExt;
    }

    /**
     * 组装待入库的文件实体，上传者取自当前登录上下文而非前端传参。
     */
    private FileInfo buildFileInfo(
            MultipartFile file, String fileMd5, Long fileSize, String fileExt, FileStorageService.StoredFile stored) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileMd5(fileMd5);
        fileInfo.setOriginalName(resolveOriginalName(file, stored.storedName()));
        fileInfo.setStoredName(stored.storedName());
        fileInfo.setFileExt(fileExt);
        fileInfo.setMimeType(resolveMimeType(file));
        fileInfo.setFileSize(fileSize);
        fileInfo.setStorageType(FileInfo.STORAGE_TYPE_LOCAL);
        fileInfo.setStoragePath(stored.storagePath());
        fileInfo.setUploaderId(UserContextHolder.getRequiredUserId());
        fileInfo.setRefCount(1);
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }

    /**
     * 取原始文件名末段并限长；缺失时回退存储名，保证列非空且不含路径。
     */
    private String resolveOriginalName(MultipartFile file, String storedName) {
        String original = file.getOriginalFilename();
        if (!StringUtils.hasText(original)) {
            return storedName;
        }
        String pureName = Paths.get(original).getFileName().toString();
        return pureName.length() > MAX_ORIGINAL_NAME_LENGTH
                ? pureName.substring(0, MAX_ORIGINAL_NAME_LENGTH)
                : pureName;
    }

    /**
     * 记录客户端上报的 MIME 类型，空则存 null；仅作元数据，类型校验以扩展名白名单为准。
     */
    private String resolveMimeType(MultipartFile file) {
        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        return contentType.length() > MAX_MIME_TYPE_LENGTH
                ? contentType.substring(0, MAX_MIME_TYPE_LENGTH)
                : contentType;
    }

    /**
     * 将文件实体与秒传标志组装为上传响应 VO，不直接对外暴露 Entity。
     */
    private FileUploadVO toUploadVO(FileInfo fileInfo, boolean secondUpload) {
        return new FileUploadVO(
                fileInfo.getId(),
                fileInfo.getFileMd5(),
                fileInfo.getOriginalName(),
                fileInfo.getFileSize(),
                fileInfo.getFileExt(),
                secondUpload);
    }

    /**
     * 读取文件 MD5 去重缓存，返回缓存的 fileId；缓存不可用时降级返回 null，交由查库。
     */
    private Long getCachedFileId(String fileMd5, Long fileSize) {
        try {
            String value = stringRedisTemplate.opsForValue()
                    .get(RedisKeyConstants.fileMd5Cache(fileMd5, fileSize));
            return StringUtils.hasText(value) ? Long.valueOf(value) : null;
        } catch (RuntimeException ex) {
            // 缓存是加速项而非正确性依赖，异常时记录日志并降级为查库。
            log.warn("读取文件 MD5 缓存失败: md5={}, size={}", fileMd5, fileSize, ex);
            return null;
        }
    }

    /**
     * 写入文件 MD5 去重缓存；失败不影响主流程，仅记录日志。
     */
    private void cacheFileId(String fileMd5, Long fileSize, Long fileId) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstants.fileMd5Cache(fileMd5, fileSize),
                    String.valueOf(fileId),
                    FILE_MD5_CACHE_TTL);
        } catch (RuntimeException ex) {
            log.warn("写入文件 MD5 缓存失败: md5={}, size={}", fileMd5, fileSize, ex);
        }
    }
}
