package com.john.campus.service;

import com.john.campus.vo.FileCheckVO;
import com.john.campus.vo.FileUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传业务接口，负责校验、MD5 去重、秒传与落盘入库的编排。
 */
public interface FileService {

    /**
     * 上传文件：校验 → 计算 MD5 → 去重（命中秒传，未命中落盘入库）。
     */
    FileUploadVO upload(MultipartFile file);

    /**
     * MD5 预检：按 md5 + size 判断是否可秒传，返回是否存在及命中的文件 ID。
     */
    FileCheckVO checkByMd5AndSize(String fileMd5, Long fileSize);
}
