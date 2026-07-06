package com.john.campus.mapper;

import com.john.campus.entity.AuditRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 审核记录表数据访问接口，负责写入和查询资料状态流转审计轨迹。
 */
public interface AuditRecordMapper {

    /**
     * 插入审核记录，数据库自增主键会回填到 auditRecord.id。
     */
    int insert(AuditRecord auditRecord);

    /**
     * 按资料 ID 查询审核历史，供管理员追踪某份资料的完整审核过程。
     */
    List<AuditRecord> selectByResourceId(@Param("resourceId") Long resourceId);
}
