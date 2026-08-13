package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.BackupService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.BackupRecord;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

/** 数据备份（实施方案 §3.6 / §5.6）。 */
@RestController
@RequestMapping("/api/admin/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:backup')")
    public ApiResponse<PageResult<BackupRecord>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(backupService.list(page - 1, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:backup')")
    @AuditLog(module = "备份", action = "手动备份")
    public ApiResponse<Map<String, Object>> trigger() {
        BackupRecord record = backupService.trigger("manual", SecurityUtils.currentUser().getId());
        return ApiResponse.ok(Map.of("id", record.getId()));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('system:backup')")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Path file = backupService.resolveDownloadFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + file.getFileName())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:backup')")
    @AuditLog(module = "备份", action = "删除备份")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        backupService.delete(id);
        return ApiResponse.ok();
    }
}
