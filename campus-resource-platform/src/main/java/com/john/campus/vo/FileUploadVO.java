package com.john.campus.vo;

/**
 * 文件上传响应对象，返回文件标识与是否命中秒传。
 *
 * @param fileId 文件 ID，供后续创建资料时引用
 * @param fileMd5 文件 MD5
 * @param originalName 原始文件名
 * @param fileSize 文件大小（字节）
 * @param fileExt 文件扩展名
 * @param secondUpload 是否命中秒传（未实际落盘）
 */
public record FileUploadVO(
        Long fileId,
        String fileMd5,
        String originalName,
        Long fileSize,
        String fileExt,
        boolean secondUpload) {
}
