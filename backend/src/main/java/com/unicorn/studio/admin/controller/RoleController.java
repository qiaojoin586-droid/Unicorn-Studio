package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.RoleService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.SysRole;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 角色与权限码管理（实施方案 §3.6）。 */
@RestController
@RequestMapping("/api/admin")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    public record RoleRequest(@NotBlank(message = "角色编码不能为空") String code,
                              @NotBlank(message = "角色名称不能为空") String name,
                              String description,
                              Integer status,
                              List<Long> permissionIds) {
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:list')")
    public ApiResponse<List<RoleService.RoleItem>> list() {
        return ApiResponse.ok(roleService.listAll());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:create')")
    @AuditLog(module = "角色", action = "新增角色")
    public ApiResponse<Map<String, Object>> create(@RequestBody RoleRequest request) {
        SysRole role = roleService.create(request.code(), request.name(),
                request.description(), request.permissionIds());
        return ApiResponse.ok(Map.of("id", role.getId()));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:edit')")
    @AuditLog(module = "角色", action = "编辑角色")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody RoleRequest request) {
        roleService.update(id, request.name(), request.description(),
                request.status(), request.permissionIds());
        return ApiResponse.ok();
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:delete')")
    @AuditLog(module = "角色", action = "删除角色")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    /** 权限码按模块分组（角色编辑弹窗的勾选树数据源） */
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:list')")
    public ApiResponse<Map<String, List<Map<String, Object>>>> permissions() {
        return ApiResponse.ok(roleService.permissionTree());
    }
}
