package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.JoinPageService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.JoinPageVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 「加入我们」页面内容管理（V17）。 */
@RestController
@RequestMapping("/api/admin/join")
public class JoinAdminController {

    private final JoinPageService joinPageService;

    public JoinAdminController(JoinPageService joinPageService) {
        this.joinPageService = joinPageService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('join:view')")
    public ApiResponse<JoinPageVO> get() {
        return ApiResponse.ok(joinPageService.load());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('join:edit')")
    @AuditLog(module = "加入我们", action = "更新页面内容")
    public ApiResponse<Void> update(@RequestBody JoinPageVO form) {
        joinPageService.save(form);
        return ApiResponse.ok();
    }
}
