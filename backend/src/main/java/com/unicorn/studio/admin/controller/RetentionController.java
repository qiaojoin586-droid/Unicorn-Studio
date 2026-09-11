package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.RetentionService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据保留策略管理（V14）。
 * <p>
 * 挂在 /api/admin/** 下复用后台 JWT 过滤器链（AdminSecurityConfig @Order(1)）：
 * 审计日志与 SQL 监控数据仅保留最近 N 天，N 在此配置（默认 7，可选 7/15/30/90）。
 * 「立即清理」本身也是 @AuditLog 写接口，其产生的审计行/文件同样受本保留期约束（下一轮被清理）。
 */
@RestController
@RequestMapping("/api/admin/retention")
public class RetentionController {

    private final RetentionService retentionService;

    public RetentionController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    /** 状态总览：配置 + 上次执行摘要 + 下次执行时间。 */
    @GetMapping
    @PreAuthorize("hasAuthority('retention:view')")
    public ApiResponse<RetentionService.RetentionStatus> status() {
        return ApiResponse.ok(retentionService.status());
    }

    /** 清理预览（无副作用）：待删除审计行数 / 待裁剪日志目录数，供二次确认展示。 */
    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('retention:view')")
    public ApiResponse<RetentionService.RetentionPreview> preview() {
        return ApiResponse.ok(retentionService.preview());
    }

    /** 保存保留策略（档位校验在服务层，非法档位返回 400）。 */
    @PutMapping
    @PreAuthorize("hasAuthority('retention:edit')")
    @AuditLog(module = "数据保留", action = "更新保留策略")
    public ApiResponse<Void> update(@RequestBody RetentionConfigRequest form) {
        retentionService.saveConfig(form == null ? null : form.days(),
                form == null ? null : form.enabled());
        return ApiResponse.ok();
    }

    /**
     * 立即清理（手动触发）。
     *
     * @param dryRun           true=仅预演，不删除任何数据
     * @param resetSqlMonitor  是否强制重置 SQL 监控统计（不受「满 N 天」节流限制）
     */
    @PostMapping("/cleanup")
    @PreAuthorize("hasAuthority('retention:run')")
    @AuditLog(module = "数据保留", action = "执行数据清理")
    public ApiResponse<RetentionService.RetentionRunReport> cleanup(
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean resetSqlMonitor) {
        return ApiResponse.ok(retentionService.cleanup(dryRun, "manual", resetSqlMonitor));
    }

    /** 配置提交体。 */
    public record RetentionConfigRequest(Integer days, Boolean enabled) {
    }
}
