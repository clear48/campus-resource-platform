package com.john.campus.mapper;

import com.john.campus.entity.FileInfo;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 文件信息表数据访问接口，SQL 统一维护在 FileInfoMapper.xml。
 */
public interface FileInfoMapper {

    /**
     * 按文件 ID 查询正常文件，供资料创建时校验 fileId 是否可引用。
     */
    FileInfo selectNormalById(@Param("id") Long id);

    /**
     * 按 MD5 和文件大小查询文件，命中 uk_file_md5_size 唯一索引，用于秒传去重判断。
     */
    FileInfo selectByMd5AndSize(@Param("fileMd5") String fileMd5, @Param("fileSize") Long fileSize);

    /**
     * 插入新文件，数据库自增主键会回填到 fileInfo.id。
     */
    int insert(FileInfo fileInfo);

    /**
     * 秒传命中时对已存在文件的引用次数加一。
     */
    int increaseRefCount(@Param("id") Long id);

    /** 按主键游标扫描正常本地文件，避免定时巡检使用大OFFSET。 */
    List<FileInfo> selectNormalLocalAfterId(@Param("lastId") Long lastId, @Param("limit") int limit);

    /** 扫描尚无READY副本的正常本地文件，MySQL作为可重放的后台任务来源。 */
    List<FileInfo> selectWithoutReadyBackupAfterId(@Param("lastId") Long lastId, @Param("limit") int limit);
}
