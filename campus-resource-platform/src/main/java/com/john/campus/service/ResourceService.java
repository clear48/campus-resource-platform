package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceCreateDTO;
import com.john.campus.vo.MyResourceVO;
import com.john.campus.vo.ResourceCreateVO;
import com.john.campus.vo.ResourceDetailVO;

/**
 * 资料业务接口，当前先提供创建资料能力，查询和审核能力后续按模块步骤补充。
 */
public interface ResourceService {

    /**
     * 创建资料：校验文件、分类和重复提交后，将资料写入待审核状态。
     */
    ResourceCreateVO create(ResourceCreateDTO dto);

    /**
     * 查询公开资料详情，只允许返回已审核通过资料。
     */
    ResourceDetailVO getPublicDetail(Long resourceId);

    /**
     * 查询当前登录用户上传的资料列表，支持按审核状态筛选。
     */
    PageResult<MyResourceVO> listMyResources(Integer status, PageQuery pageQuery);
}
