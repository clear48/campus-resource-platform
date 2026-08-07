package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.PageResult;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.entity.Category;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.CategoryMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.mapper.UserFileAuthorizationMapper;
import com.john.campus.service.ResourceDetailCacheService;
import com.john.campus.service.ResourceService;
import com.john.campus.vo.MyResourceVO;
import com.john.campus.vo.ResourceCreateVO;
import com.john.campus.vo.ResourceDetailVO;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 资料业务实现，当前只负责把已上传文件转换为待审核资料记录。
 */
@Service
public class ResourceServiceImpl implements ResourceService {

    /**
     * 资料标题、课程名、标签等长度限制与数据库字段和 DTO 校验保持一致。
     */
    private static final int MAX_TITLE_LENGTH = 150;
    private static final int MAX_COURSE_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 2000;
    private static final int MAX_TAG_COUNT = 10;
    private static final int MAX_TAG_LENGTH = 20;
    private static final int MAX_TAGS_STORAGE_LENGTH = 255;
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String STATUS_NAME_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String CREATE_SUCCESS_MESSAGE = "资料已创建，等待管理员审核";

    /**
     * 资料表访问入口。
     */
    private final ResourceMapper resourceMapper;
    /**
     * 文件表访问入口，用于校验 fileId 是否指向正常文件。
     */
    private final FileInfoMapper fileInfoMapper;
    /**
     * 分类表访问入口，用于校验 categoryId 是否指向启用分类。
     */
    private final CategoryMapper categoryMapper;
    /**
     * 用户文件授权关系，防止使用猜测或泄露的 fileId 引用他人文件。
     */
    private final UserFileAuthorizationMapper userFileAuthorizationMapper;
    /**
     * 公开详情共享缓存；部分 Mapper 切片测试未装配 Redis 组件时允许为空并直接回源 MySQL。
     */
    private final ResourceDetailCacheService resourceDetailCacheService;

    @Autowired
    public ResourceServiceImpl(
            ResourceMapper resourceMapper,
            FileInfoMapper fileInfoMapper,
            CategoryMapper categoryMapper,
            UserFileAuthorizationMapper userFileAuthorizationMapper,
            ObjectProvider<ResourceDetailCacheService> resourceDetailCacheServiceProvider) {
        this.resourceMapper = resourceMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.categoryMapper = categoryMapper;
        this.userFileAuthorizationMapper = userFileAuthorizationMapper;
        this.resourceDetailCacheService = resourceDetailCacheServiceProvider.getIfAvailable();
    }

    /**
     * 保留现有纯 Mapper 测试的构造入口；生产环境由 Spring 使用带缓存提供器的构造方法。
     */
    public ResourceServiceImpl(
            ResourceMapper resourceMapper,
            FileInfoMapper fileInfoMapper,
            CategoryMapper categoryMapper,
            UserFileAuthorizationMapper userFileAuthorizationMapper) {
        this.resourceMapper = resourceMapper;
        this.fileInfoMapper = fileInfoMapper;
        this.categoryMapper = categoryMapper;
        this.userFileAuthorizationMapper = userFileAuthorizationMapper;
        this.resourceDetailCacheService = null;
    }

    /**
     * 创建资料主流程：只写 resource 单表，先保证待审核资料这个业务主体可靠落库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResourceCreateVO create(ResourceCreateDTO dto) {
        validateCreateDTO(dto);
        Long uploaderId = UserContextHolder.getRequiredUserId();

        FileInfo fileInfo = fileInfoMapper.selectNormalById(dto.getFileId());
        if (fileInfo == null) {
            // 只允许引用正常文件，避免资料指向不存在或已删除的物理文件。
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或已删除");
        }
        if (!userFileAuthorizationMapper.exists(uploaderId, dto.getFileId())) {
            // 对外仍按不可用文件处理，避免借错误差异探测其他用户的 fileId。
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或当前用户未获得引用权限");
        }

        Category category = categoryMapper.selectEnabledById(dto.getCategoryId());
        if (category == null) {
            // 创建资料必须引用启用分类，防止新资料继续挂到停用分类上。
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "分类不存在或已禁用");
        }

        long duplicateCount = resourceMapper.countActiveByUploaderAndFileId(uploaderId, dto.getFileId());
        if (duplicateCount > 0) {
            // 待审核或已通过资料仍有业务效力，同一用户重复提交同一文件会造成审核噪音。
            throw new BusinessException(ErrorCode.DATA_DUPLICATE, "已提交过相同文件的待审核或已通过资料");
        }

        Resource resource = buildPendingResource(dto, uploaderId);
        resourceMapper.insert(resource);
        return toCreateVO(resource);
    }

    /**
     * 公开详情只服务已审核通过资料，未通过资料不能被游客或普通用户消费。
     */
    @Override
    public ResourceDetailVO getPublicDetail(Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料 ID 不合法");
        }

        if (resourceDetailCacheService == null) {
            return loadPublicDetailFromDatabase(resourceId);
        }
        return resourceDetailCacheService.getOrLoad(
                resourceId,
                () -> loadPublicDetailFromDatabase(resourceId));
    }

    /**
     * MySQL loader 只承担原有可见性校验与 VO 组装；是否加锁、二次检查和回填统一由缓存 Service 决定。
     */
    private ResourceDetailVO loadPublicDetailFromDatabase(Long resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        if (!resource.isVisibleToPublic()) {
            // 状态判断放在 Service 层，便于明确区分“找不到”和“不可公开访问”。
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料未审核通过或已下架");
        }

        Category category = categoryMapper.selectEnabledById(resource.getCategoryId());
        String categoryName = category == null ? null : category.getCategoryName();
        return toDetailVO(resource, categoryName);
    }

    /**
     * 我的上传资料只能查询当前登录用户的数据，避免用户通过参数越权访问他人资料。
     */
    @Override
    public PageResult<MyResourceVO> listMyResources(Integer status, PageQuery pageQuery) {
        validateResourceStatus(status);
        int pageNo = resolvePageNo(pageQuery);
        int pageSize = resolvePageSize(pageQuery);
        int offset = (pageNo - 1) * pageSize;
        Long uploaderId = UserContextHolder.getRequiredUserId();

        List<Resource> resources = resourceMapper.selectByUploader(uploaderId, status, offset, pageSize);
        long total = resourceMapper.countByUploader(uploaderId, status);
        List<MyResourceVO> records = resources.stream()
                .map(this::toMyResourceVO)
                .toList();
        return PageResult.of(records, pageNo, pageSize, total);
    }

    /**
     * Service 层做一层兜底校验，避免未来绕过 Controller Validation 时写入脏数据。
     */
    private void validateCreateDTO(ResourceCreateDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "创建资料参数不能为空");
        }
        if (dto.getFileId() == null || dto.getFileId() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件 ID 不合法");
        }
        if (dto.getCategoryId() == null || dto.getCategoryId() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类 ID 不合法");
        }
        if (!StringUtils.hasText(dto.getTitle()) || dto.getTitle().trim().length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料标题不能为空且长度不能超过 150");
        }
        if (!StringUtils.hasText(dto.getCourseName()) || dto.getCourseName().trim().length() > MAX_COURSE_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "课程名称不能为空且长度不能超过 100");
        }
        if (StringUtils.hasText(dto.getDescription())
                && dto.getDescription().trim().length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料简介长度不能超过 2000");
        }
        if (!isAllowedResourceType(dto.getResourceType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料类型不合法");
        }
    }

    /**
     * 资料类型必须落在数据库 CHECK 约束允许的范围内。
     */
    private boolean isAllowedResourceType(Integer resourceType) {
        if (resourceType == null) {
            return false;
        }
        return Resource.TYPE_COURSEWARE == resourceType
                || Resource.TYPE_NOTE == resourceType
                || Resource.TYPE_EXAM == resourceType
                || Resource.TYPE_LAB_REPORT == resourceType
                || Resource.TYPE_COURSE_DESIGN == resourceType
                || Resource.TYPE_OTHER == resourceType;
    }

    /**
     * 列表筛选状态必须是 resource 表允许的审核状态。
     */
    private void validateResourceStatus(Integer status) {
        if (status == null) {
            return;
        }
        boolean valid = Resource.STATUS_PENDING_REVIEW == status
                || Resource.STATUS_APPROVED == status
                || Resource.STATUS_REJECTED == status
                || Resource.STATUS_OFFLINE == status
                || Resource.STATUS_DELETED == status;
        if (!valid) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料状态不合法");
        }
    }

    /**
     * 解析页码，Controller 未接入前先在 Service 层兜底默认值和边界。
     */
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

    /**
     * 解析页大小，限制最大值保护数据库和响应体大小。
     */
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

    /**
     * 构造待审核资料实体，所有服务端控制字段都在后端设置，不信任前端传参。
     */
    private Resource buildPendingResource(ResourceCreateDTO dto, Long uploaderId) {
        Resource resource = new Resource();
        resource.setTitle(dto.getTitle().trim());
        resource.setDescription(trimToNull(dto.getDescription()));
        resource.setCategoryId(dto.getCategoryId());
        resource.setCourseName(dto.getCourseName().trim());
        resource.setResourceType(dto.getResourceType());
        resource.setTags(normalizeTags(dto.getTags()));
        resource.setFileId(dto.getFileId());
        resource.setUploaderId(uploaderId);
        resource.setStatus(Resource.STATUS_PENDING_REVIEW);
        resource.setViewCount(0L);
        resource.setDownloadCount(0L);
        resource.setFavoriteCount(0L);
        resource.setHotScore(BigDecimal.ZERO);
        return resource;
    }

    /**
     * 清洗标签：去空白、去重、保序，并转换为 resource.tags 使用的逗号分隔字符串。
     */
    private String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        if (tags.size() > MAX_TAG_COUNT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标签数量不能超过 10 个");
        }

        Set<String> normalizedTags = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String trimmedTag = tag.trim();
            if (trimmedTag.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "单个标签长度不能超过 20");
            }
            normalizedTags.add(trimmedTag);
        }
        if (normalizedTags.isEmpty()) {
            return null;
        }

        String joinedTags = String.join(",", normalizedTags);
        if (joinedTags.length() > MAX_TAGS_STORAGE_LENGTH) {
            // 数据库 resource.tags 是 VARCHAR(255)，这里提前拒绝而不是依赖数据库截断或报错。
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标签总长度不能超过 255");
        }
        return joinedTags;
    }

    /**
     * 空白字符串统一转 null，减少数据库中保存无意义空字符串。
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 将数据库逗号分隔标签转换为前端使用的列表结构。
     */
    private List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 创建响应只暴露前端需要的信息，不返回完整 Entity。
     */
    private ResourceCreateVO toCreateVO(Resource resource) {
        return new ResourceCreateVO(
                resource.getId(),
                resource.getFileId(),
                resource.getStatus(),
                STATUS_NAME_PENDING_REVIEW,
                CREATE_SUCCESS_MESSAGE
        );
    }

    /**
     * 公开详情响应不返回 storage_path、stored_name 等内部文件存储字段。
     */
    private ResourceDetailVO toDetailVO(Resource resource, String categoryName) {
        return new ResourceDetailVO(
                resource.getId(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getCategoryId(),
                categoryName,
                resource.getCourseName(),
                resource.getResourceType(),
                splitTags(resource.getTags()),
                resource.getStatus(),
                resource.getDownloadCount(),
                resource.getFavoriteCount(),
                resource.getHotScore(),
                resource.getCreatedAt(),
                null
        );
    }

    /**
     * 我的上传列表只返回状态追踪所需字段，避免列表响应过重。
     */
    private MyResourceVO toMyResourceVO(Resource resource) {
        return new MyResourceVO(
                resource.getId(),
                resource.getTitle(),
                resource.getCourseName(),
                resource.getStatus(),
                resource.getRejectReason(),
                resource.getOfflineReason(),
                resource.getCreatedAt()
        );
    }
}
