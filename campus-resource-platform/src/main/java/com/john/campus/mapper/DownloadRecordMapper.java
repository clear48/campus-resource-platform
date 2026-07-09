package com.john.campus.mapper;

import com.john.campus.entity.DownloadRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 下载记录表数据访问接口，负责写入下载行为流水并支撑「我的下载记录」查询。
 * 只做数据库操作，不承载下载权限、限流和计数等业务判断。
 */
public interface DownloadRecordMapper {

    /**
     * 插入下载记录，数据库自增主键会回填到 downloadRecord.id。
     */
    int insert(DownloadRecord downloadRecord);

    /**
     * 按主键查询下载记录，供文件流接口做归属校验和物理文件定位。
     */
    DownloadRecord selectById(@Param("id") Long id);

    /**
     * 按下载用户分页查询下载记录，只返回该用户自己的记录，避免越权。
     */
    List<DownloadRecord> selectByUser(
            @Param("userId") Long userId,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    /**
     * 统计某用户下载记录总数，与 selectByUser 使用同一过滤条件，供分页 total 使用。
     */
    long countByUser(@Param("userId") Long userId);
}
