package com.john.campus.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 上传文件内容校验入口，交叉验证扩展名、客户端 MIME 和服务端识别出的实际结构。
 */
public interface FileContentValidator {

    /**
     * 校验文件内容并返回可安全持久化的服务端 MIME，不信任客户端上报值作为元数据。
     */
    ValidatedFileType validate(MultipartFile file, String fileExtension);

    /**
     * @param mimeType 服务端按扩展名与内容共同确认的受控 MIME
     */
    record ValidatedFileType(String mimeType) {
    }
}
