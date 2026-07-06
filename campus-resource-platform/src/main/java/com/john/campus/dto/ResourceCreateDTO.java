package com.john.campus.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 创建资料请求参数，承载文件 ID、分类、课程和展示信息。
 */
@Getter
@Setter
public class ResourceCreateDTO {

    /**
     * 已上传成功的文件 ID，资料模块只引用文件，不直接接收 MultipartFile。
     */
    @NotNull(message = "文件 ID 不能为空")
    @Positive(message = "文件 ID 必须大于 0")
    private Long fileId;

    /**
     * 资料标题，用于列表、详情和后续搜索。
     */
    @NotBlank(message = "资料标题不能为空")
    @Size(max = 150, message = "资料标题长度不能超过 150")
    private String title;

    /**
     * 资料简介允许为空；非空时限制长度，避免超过 resource.description 的合理展示范围。
     */
    @Size(max = 2000, message = "资料简介长度不能超过 2000")
    private String description;

    /**
     * 分类 ID，Service 层会校验分类是否存在且启用。
     */
    @NotNull(message = "分类 ID 不能为空")
    @Positive(message = "分类 ID 必须大于 0")
    private Long categoryId;

    /**
     * 课程名称，后续搜索筛选会依赖该字段。
     */
    @NotBlank(message = "课程名称不能为空")
    @Size(max = 100, message = "课程名称长度不能超过 100")
    private String courseName;

    /**
     * 资料类型：1课件 2笔记 3真题 4实验报告 5课程设计 99其他。
     */
    @NotNull(message = "资料类型不能为空")
    private Integer resourceType;

    /**
     * 标签列表，Service 层会负责清洗、去重并转换为逗号分隔字符串。
     */
    @Size(max = 10, message = "标签数量不能超过 10 个")
    private List<@Size(max = 20, message = "单个标签长度不能超过 20") String> tags;

    /**
     * Validation 扩展校验：资料类型只能取业务允许的枚举值。
     */
    @AssertTrue(message = "资料类型不合法")
    public boolean isResourceTypeValid() {
        if (resourceType == null) {
            return true;
        }
        return resourceType == 1
                || resourceType == 2
                || resourceType == 3
                || resourceType == 4
                || resourceType == 5
                || resourceType == 99;
    }
}
