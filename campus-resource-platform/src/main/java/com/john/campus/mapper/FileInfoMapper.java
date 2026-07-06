package com.john.campus.mapper;

import com.john.campus.entity.FileInfo;
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
}
