package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.PublicUserAdminService;
import com.unicorn.studio.admin.service.PublicUserAdminService.UserEdit;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台用户管理（实施方案 §3.5）。 */
@RestController
@RequestMapping("/api/admin/users")
public class PublicUserAdminController {

    private final PublicUserAdminService userService;

    public PublicUserAdminController(PublicUserAdminService userService) {
        this.userService = userService;
    }

    public record BanRequest(@NotBlank(message = "禁言时长不能为空") String duration, String reason) {
    }

    public record BindPhoneRequest(@NotBlank(message = "手机号不能为空") String phone) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:list')")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String registerFrom,
            @RequestParam(required = false) String registerTo) {
        return ApiResponse.ok(userService.list(keyword, status, registerFrom, registerTo, page - 1, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(userService.detail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:edit')")
    @AuditLog(module = "用户", action = "编辑用户资料")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody UserEdit request) {
        userService.update(id, request);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/phone")
    @PreAuthorize("hasAuthority('user:bind-phone')")
    @AuditLog(module = "用户", action = "为旧账号绑定手机号")
    public ApiResponse<Void> bindPhone(@PathVariable Long id, @Valid @RequestBody BindPhoneRequest request) {
        userService.bindPhone(id, request.phone());
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/ban")
    @PreAuthorize("hasAuthority('user:ban')")
    @AuditLog(module = "用户", action = "禁言用户")
    public ApiResponse<Void> ban(@PathVariable Long id, @Valid @RequestBody BanRequest request) {
        userService.ban(id, request.duration(), request.reason());
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/unban")
    @PreAuthorize("hasAuthority('user:ban')")
    @AuditLog(module = "用户", action = "解禁用户")
    public ApiResponse<Void> unban(@PathVariable Long id) {
        userService.unban(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    @AuditLog(module = "用户", action = "注销用户")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok();
    }
}
