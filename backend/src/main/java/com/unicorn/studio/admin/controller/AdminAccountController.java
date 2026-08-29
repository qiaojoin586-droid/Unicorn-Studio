package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.AdminAccountService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.SysUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理员管理 + 个人中心（实施方案 §3.6）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminAccountController {

    private final AdminAccountService accountService;

    public AdminAccountController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    public record CreateAdminRequest(@NotBlank(message = "用户名不能为空") String username,
                                     @NotBlank(message = "密码不能为空") @Size(min = 8, message = "密码至少 8 位") String password,
                                     String nickname,
                                     @NotBlank(message = "至少分配一个角色") List<Long> roleIds) {
    }

    public record UpdateAdminRequest(String nickname, Integer status, List<Long> roleIds) {
    }

    public record UpdateProfileRequest(String nickname, String avatar) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "原密码不能为空") String oldPassword,
                                        @NotBlank(message = "新密码不能为空") @Size(min = 8, message = "新密码至少 8 位") String newPassword) {
    }

    @GetMapping("/admins")
    @PreAuthorize("hasAuthority('admin:list')")
    public ApiResponse<PageResult<AdminAccountService.AdminItem>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(accountService.list(keyword, page - 1, size));
    }

    @PostMapping("/admins")
    @PreAuthorize("hasAuthority('admin:create')")
    @AuditLog(module = "管理员", action = "新增管理员")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateAdminRequest request) {
        SysUser user = accountService.create(request.username(), request.password(),
                request.nickname(), request.roleIds());
        return ApiResponse.ok(Map.of("id", user.getId()));
    }

    @PutMapping("/admins/{id}")
    @PreAuthorize("hasAuthority('admin:edit')")
    @AuditLog(module = "管理员", action = "编辑管理员")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody UpdateAdminRequest request) {
        accountService.update(id, request.nickname(), request.status(), request.roleIds(),
                SecurityUtils.currentUser());
        return ApiResponse.ok();
    }

    @DeleteMapping("/admins/{id}")
    @PreAuthorize("hasAuthority('admin:delete')")
    @AuditLog(module = "管理员", action = "删除管理员")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        accountService.delete(id, SecurityUtils.currentUser());
        return ApiResponse.ok();
    }

    @GetMapping("/profile")
    public ApiResponse<Map<String, Object>> profile() {
        return ApiResponse.ok(accountService.profile(SecurityUtils.currentUser()));
    }

    @PutMapping("/profile")
    public ApiResponse<Void> updateProfile(@RequestBody UpdateProfileRequest request) {
        accountService.updateProfile(SecurityUtils.currentUser(), request.nickname(), request.avatar());
        return ApiResponse.ok();
    }

    @PutMapping("/profile/password")
    @AuditLog(module = "个人中心", action = "修改密码")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        accountService.changePassword(SecurityUtils.currentUser(),
                request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }
}
