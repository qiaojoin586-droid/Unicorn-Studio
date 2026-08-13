package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.SiteSettingsService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 系统设置（实施方案 §3.6）。 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingController {

    private final SiteSettingsService settingsService;

    public SettingController(SiteSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:settings')")
    public ApiResponse<Map<String, Map<String, String>>> list() {
        return ApiResponse.ok(settingsService.listGrouped());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:settings')")
    @AuditLog(module = "系统设置", action = "更新设置")
    public ApiResponse<Void> update(@RequestBody List<SiteSettingsService.KeyValueRequest> items) {
        settingsService.updateAll(items);
        return ApiResponse.ok();
    }
}
