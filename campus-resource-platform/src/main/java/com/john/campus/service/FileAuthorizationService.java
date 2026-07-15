package com.john.campus.service;

import com.john.campus.entity.FileInfo;

/**
 * 文件元数据与用户授权的事务边界。
 */
public interface FileAuthorizationService {

    /** 首次落库文件，并在同一事务中授予上传者引用权限。 */
    void createAuthorizedFile(FileInfo fileInfo, Long userId);

    /** 真实上传命中已有内容时，原子增加引用次数并授予当前用户权限。 */
    void authorizeExistingFile(Long fileId, Long userId);

    /** 判断用户是否可以使用指定 fileId 创建资料。 */
    boolean isAuthorized(Long userId, Long fileId);
}
