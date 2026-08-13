package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.FileService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.UploadFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 文件上传 + 媒体库（实施方案 §3.6 / §5.5）。 */
@RestController
@RequestMapping("/api/admin/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('file:upload')")
    @AuditLog(module = "文件", action = "上传文件")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        UploadFile saved = fileService.upload(file, SecurityUtils.currentUser().getId());
        return ApiResponse.ok(Map.of(
                "url", saved.getUrl(),
                "name", saved.getFileName(),
                "size", saved.getSize()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('file:list')")
    public ApiResponse<PageResult<UploadFile>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(fileService.list(keyword, page - 1, size));
    }
}
