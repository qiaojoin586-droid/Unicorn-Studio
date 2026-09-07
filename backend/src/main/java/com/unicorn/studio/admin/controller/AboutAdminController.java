package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.AboutPageService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.AboutPageVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 「关于我们」页面内容管理（V12）。 */
@RestController
@RequestMapping("/api/admin/about")
public class AboutAdminController {

    private final AboutPageService aboutPageService;

    public AboutAdminController(AboutPageService aboutPageService) {
        this.aboutPageService = aboutPageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('about:view')")
    public ApiResponse<AboutPageVO> get() {
        return ApiResponse.ok(aboutPageService.load());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('about:edit')")
    @AuditLog(module = "关于我们", action = "更新页面内容")
    public ApiResponse<Void> update(@RequestBody AboutPageVO form) {
        aboutPageService.save(form);
        return ApiResponse.ok();
    }
}
