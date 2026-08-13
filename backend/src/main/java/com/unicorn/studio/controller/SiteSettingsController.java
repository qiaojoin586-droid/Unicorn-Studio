package com.unicorn.studio.controller;

import com.unicorn.studio.admin.service.SiteSettingsService;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 前台站点设置（实施方案 §3.7：缓存 10 分钟，不含敏感配置）。 */
@RestController
@RequestMapping("/api/site/settings")
public class SiteSettingsController {

    private final SiteSettingsService settingsService;

    public SiteSettingsController(SiteSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ApiResponse<Map<String, Map<String, String>>> get() {
        return ApiResponse.ok(settingsService.publicSettings());
    }
}
