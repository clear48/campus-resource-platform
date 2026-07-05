package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.CategoryService;
import com.john.campus.vo.CategoryVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分类查询接口，当前仅提供上传前需要的公开只读查询能力。
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    /**
     * 分类业务服务，负责参数边界和 Entity 到 VO 的转换。
     */
    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 查询某个父分类下的启用分类；该接口不需要登录，由 WebMvcConfig 排除 JWT 拦截。
     */
    @GetMapping
    public ApiResponse<List<CategoryVO>> list(@RequestParam(defaultValue = "0") Long parentId) {
        return ApiResponse.success(categoryService.listEnabledCategoriesByParentId(parentId));
    }
}
