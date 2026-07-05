package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.FileService;
import com.john.campus.vo.FileCheckVO;
import com.john.campus.vo.FileUploadVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口入口，接收上传请求并委托给 FileService，登录态由 JWT 拦截器统一校验。
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    /**
     * 文件上传业务服务，Controller 只负责接收请求和返回统一结果。
     */
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /**
     * 上传文件，返回文件标识与是否命中秒传；上传者由 Service 从登录上下文获取。
     */
    @PostMapping
    public ApiResponse<FileUploadVO> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(fileService.upload(file));
    }

    /**
     * MD5 预检，供前端在上传前判断是否可秒传，避免重复上传大文件。
     */
    @GetMapping("/check")
    public ApiResponse<FileCheckVO> check(@RequestParam String fileMd5, @RequestParam Long fileSize) {
        return ApiResponse.success(fileService.checkByMd5AndSize(fileMd5, fileSize));
    }
}
