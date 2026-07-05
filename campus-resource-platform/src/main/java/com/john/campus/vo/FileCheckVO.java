package com.john.campus.vo;

/**
 * 文件 MD5 预检响应对象，告知是否可秒传及命中的文件 ID。
 *
 * @param secondUpload 是否已存在相同文件（可秒传）
 * @param fileId 命中时的文件 ID，未命中为 null
 */
public record FileCheckVO(boolean secondUpload, Long fileId) {
}
