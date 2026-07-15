package com.john.campus.service.impl;

import com.john.campus.entity.FileInfo;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.UserFileAuthorizationMapper;
import com.john.campus.service.FileAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将文件引用计数和用户授权作为一个数据库事务提交，避免只完成其中一步。
 */
@Service
public class FileAuthorizationServiceImpl implements FileAuthorizationService {

    /** 首次上传授权。 */
    private static final int SOURCE_FIRST_UPLOAD = 1;
    /** 实际上传内容命中去重授权。 */
    private static final int SOURCE_CONTENT_DEDUP = 2;

    private final FileInfoMapper fileInfoMapper;
    private final UserFileAuthorizationMapper authorizationMapper;

    public FileAuthorizationServiceImpl(
            FileInfoMapper fileInfoMapper,
            UserFileAuthorizationMapper authorizationMapper) {
        this.fileInfoMapper = fileInfoMapper;
        this.authorizationMapper = authorizationMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createAuthorizedFile(FileInfo fileInfo, Long userId) {
        fileInfoMapper.insert(fileInfo);
        authorizationMapper.insertIgnore(userId, fileInfo.getId(), SOURCE_FIRST_UPLOAD);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void authorizeExistingFile(Long fileId, Long userId) {
        fileInfoMapper.increaseRefCount(fileId);
        authorizationMapper.insertIgnore(userId, fileId, SOURCE_CONTENT_DEDUP);
    }

    @Override
    public boolean isAuthorized(Long userId, Long fileId) {
        return authorizationMapper.exists(userId, fileId);
    }
}
