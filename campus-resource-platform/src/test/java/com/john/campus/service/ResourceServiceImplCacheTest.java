package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.entity.Category;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.CategoryMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.mapper.UserFileAuthorizationMapper;
import com.john.campus.service.impl.ResourceServiceImpl;
import com.john.campus.vo.ResourceDetailVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

/**
 * 资料详情缓存编排测试，验证缓存与数据库的优先级以及错误语义不被缓存接入改变。
 */
@ExtendWith(MockitoExtension.class)
class ResourceServiceImplCacheTest {

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private UserFileAuthorizationMapper userFileAuthorizationMapper;
    @Mock
    private ResourceDetailCacheService cacheService;
    @Mock
    private ObjectProvider<ResourceDetailCacheService> cacheServiceProvider;

    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        when(cacheServiceProvider.getIfAvailable()).thenReturn(cacheService);
        resourceService = new ResourceServiceImpl(
                resourceMapper,
                fileInfoMapper,
                categoryMapper,
                userFileAuthorizationMapper,
                cacheServiceProvider);
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    @Test
    void createShouldTranslateConcurrentUniqueConflictToBusinessDuplicate() {
        long uploaderId = 1L;
        long fileId = 20L;
        long categoryId = 10L;
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(fileId);
        Category category = new Category();
        category.setId(categoryId);
        ResourceCreateDTO dto = new ResourceCreateDTO();
        dto.setTitle("Java 并发资料");
        dto.setFileId(fileId);
        dto.setCategoryId(categoryId);
        dto.setCourseName("Java 程序设计");
        dto.setResourceType(Resource.TYPE_COURSEWARE);

        UserContextHolder.set(new LoginUser(uploaderId, 1, "test-jti"));
        when(fileInfoMapper.selectNormalById(fileId)).thenReturn(fileInfo);
        when(userFileAuthorizationMapper.exists(uploaderId, fileId)).thenReturn(true);
        when(categoryMapper.selectEnabledById(categoryId)).thenReturn(category);
        when(resourceMapper.countActiveByUploaderAndFileId(uploaderId, fileId)).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_resource_active_duplicate"))
                .when(resourceMapper).insert(any(Resource.class));

        assertThatThrownBy(() -> resourceService.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessage("已提交过相同文件的待审核或已通过资料")
                .extracting("code")
                .isEqualTo(ErrorCode.DATA_DUPLICATE.getCode());
    }

    @Test
    void getPublicDetailShouldReturnCacheHitWithoutQueryingDatabase() {
        ResourceDetailVO cached = buildDetail();
        when(cacheService.getOrLoad(eq(100L), any())).thenReturn(cached);

        assertThat(resourceService.getPublicDetail(100L)).isSameAs(cached);
        verifyNoInteractions(resourceMapper, categoryMapper);
        verify(cacheService).getOrLoad(eq(100L), any());
    }

    @Test
    void getPublicDetailShouldQueryDatabaseCategoryAndBackfillCacheOnMiss() {
        Resource resource = buildApprovedResource();
        Category category = new Category();
        category.setId(10L);
        category.setCategoryName("计算机基础");
        when(cacheService.getOrLoad(eq(100L), any())).thenAnswer(invocation -> load(invocation.getArgument(1)));
        when(resourceMapper.selectById(100L)).thenReturn(resource);
        when(categoryMapper.selectEnabledById(10L)).thenReturn(category);

        ResourceDetailVO result = resourceService.getPublicDetail(100L);

        assertThat(result.resourceId()).isEqualTo(100L);
        assertThat(result.categoryName()).isEqualTo("计算机基础");
        assertThat(result.tags()).containsExactly("Java", "并发");
        assertThat(result.status()).isEqualTo(Resource.STATUS_APPROVED);
        verify(cacheService).getOrLoad(eq(100L), any());
    }

    @Test
    void getPublicDetailShouldKeepNotFoundErrorAndNotCache() {
        when(cacheService.getOrLoad(eq(404L), any())).thenAnswer(invocation -> load(invocation.getArgument(1)));
        when(resourceMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> resourceService.getPublicDetail(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void getPublicDetailShouldKeepInvisibleErrorAndNotCache() {
        Resource pending = buildApprovedResource();
        pending.setStatus(Resource.STATUS_PENDING_REVIEW);
        when(cacheService.getOrLoad(eq(100L), any())).thenAnswer(invocation -> load(invocation.getArgument(1)));
        when(resourceMapper.selectById(100L)).thenReturn(pending);

        assertThatThrownBy(() -> resourceService.getPublicDetail(100L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_STATUS_INVALID.getCode());
        verifyNoInteractions(categoryMapper);
    }

    private Resource buildApprovedResource() {
        Resource resource = new Resource();
        resource.setId(100L);
        resource.setTitle("Java 并发笔记");
        resource.setDescription("线程池与锁");
        resource.setCategoryId(10L);
        resource.setCourseName("Java 程序设计");
        resource.setResourceType(Resource.TYPE_NOTE);
        resource.setTags("Java, 并发");
        resource.setStatus(Resource.STATUS_APPROVED);
        resource.setDownloadCount(20L);
        resource.setFavoriteCount(5L);
        resource.setHotScore(new BigDecimal("88.50"));
        resource.setCreatedAt(LocalDateTime.of(2026, 8, 7, 10, 0));
        return resource;
    }

    private ResourceDetailVO buildDetail() {
        return new ResourceDetailVO(
                100L, "Java 并发笔记", "线程池与锁", 10L, "计算机基础", "Java 程序设计",
                Resource.TYPE_NOTE, List.of("Java", "并发"), Resource.STATUS_APPROVED, 20L, 5L,
                new BigDecimal("88.50"), LocalDateTime.of(2026, 8, 7, 10, 0), null);
    }

    private ResourceDetailVO load(Supplier<ResourceDetailVO> loader) {
        // 缓存 mock 只负责触发业务 loader，数据库结果与异常仍由真实 Service 代码产生。
        return loader.get();
    }
}
