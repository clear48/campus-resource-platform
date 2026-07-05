package com.john.campus.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务接口：计算 MD5、生成安全存储名、落盘与删除补偿。
 * 只处理物理文件本身，不做去重和数据库操作，去重与入库由 FileService 负责。
 */
public interface FileStorageService {

    /**
     * 计算文件的 32 位十六进制 MD5，用于秒传去重判断（判断逻辑在 FileService）。
     */
    String calculateMd5(MultipartFile file);

    /**
     * 从原始文件名安全提取小写扩展名；无扩展名返回空串，非字母数字字符一律剔除。
     */
    String resolveExtension(String originalFilename);

    /**
     * 将文件落盘到存储根目录，使用 UUID + 扩展名作为存储名，禁止使用原始文件名。
     */
    StoredFile store(MultipartFile file, String fileExt);

    /**
     * 删除已落盘文件，供上传后入库失败时补偿清理，避免产生孤儿文件。
     */
    void delete(String storagePath);

    /**
     * 落盘结果：存储文件名与最终存储路径，供上层写入 file_info。
     */
    record StoredFile(String storedName, String storagePath) {
    }
}
