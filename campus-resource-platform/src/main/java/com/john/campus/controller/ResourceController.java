package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.service.ResourceService;
import com.john.campus.vo.MyResourceVO;
import com.john.campus.vo.ResourceCreateVO;
import com.john.campus.vo.ResourceDetailVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资料接口入口，负责接收 HTTP 请求并把业务编排交给 ResourceService。
 */
@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    /**
     * 资料业务服务，Controller 不直接访问 Mapper，避免请求层承载业务规则。
     */
    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    /**
     * 创建资料：登录态由 JWT 拦截器校验，上传者身份由 Service 从 UserContextHolder 获取。
     */
    @PostMapping("/resources")
    public ApiResponse<ResourceCreateVO> create(@Valid @RequestBody ResourceCreateDTO request) {
        return ApiResponse.success(resourceService.create(request));
    }

    /**
     * 查询公开资料详情：该路径在 WebMvcConfig 中匿名放行，但 Service 只返回已审核通过资料。
     */
    @GetMapping("/resources/{resourceId}")
    public ApiResponse<ResourceDetailVO> detail(@PathVariable Long resourceId) {
        return ApiResponse.success(resourceService.getPublicDetail(resourceId));
    }

    /**
     * 查询我的上传资料：只按当前登录用户查询，避免前端传 uploaderId 造成越权风险。
     */
    @GetMapping("/users/me/resources")
    public ApiResponse<PageResult<MyResourceVO>> listMyResources(
            @RequestParam(required = false) Integer status,
            @Valid PageQuery pageQuery) {
        return ApiResponse.success(resourceService.listMyResources(status, pageQuery));
    }
}
