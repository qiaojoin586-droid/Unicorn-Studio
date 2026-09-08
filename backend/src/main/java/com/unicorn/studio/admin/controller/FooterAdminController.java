package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.FooterService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.FooterVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页脚设置管理（V13）。
 * <p>
 * 挂在 /api/admin/** 下以复用后台 JWT 过滤器链（AdminSecurityConfig @Order(1)），
 * 与 /api/admin/about 保持同一约定；前台读取走公开的 GET /api/footer。
 */
@RestController
@RequestMapping("/api/admin/footer")
public class FooterAdminController {

    private final FooterService footerService;

    public FooterAdminController(FooterService footerService) {
        this.footerService = footerService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('footer:view')")
    public ApiResponse<FooterVO> get() {
        return ApiResponse.ok(footerService.load());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('footer:edit')")
    @AuditLog(module = "页脚设置", action = "更新页脚配置")
    public ApiResponse<Void> update(@RequestBody FooterVO form) {
        footerService.save(form);
        return ApiResponse.ok();
    }
}
