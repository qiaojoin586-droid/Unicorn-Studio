package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.CapabilityAdminService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Capability;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 我们培养的能力管理。 */
@RestController
@RequestMapping("/api/admin/capabilities")
public class CapabilityAdminController {

    private final CapabilityAdminService capabilityAdminService;

    public CapabilityAdminController(CapabilityAdminService capabilityAdminService) {
        this.capabilityAdminService = capabilityAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('capability:list')")
    public ApiResponse<PageResult<CapabilityAdminService.CapabilityView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 1) {
            throw BusinessException.badRequest("页码不能小于 1");
        }
        if (size < 1 || size > 100) {
            throw BusinessException.badRequest("每页数量必须在 1 到 100 之间");
        }
        return ApiResponse.ok(capabilityAdminService.list(page - 1, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('capability:create')")
    @AuditLog(module = "我们培养的能力", action = "新增能力")
    public ApiResponse<Map<String, Object>> create(
            @Valid @RequestBody CapabilityAdminService.CapabilityForm form) {
        Capability item = capabilityAdminService.create(form);
        return ApiResponse.ok(Map.of("id", item.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('capability:edit')")
    @AuditLog(module = "我们培养的能力", action = "编辑能力")
    public ApiResponse<Void> update(
            @PathVariable Long id,
            @Valid @RequestBody CapabilityAdminService.CapabilityForm form) {
        capabilityAdminService.update(id, form);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('capability:delete')")
    @AuditLog(module = "我们培养的能力", action = "删除能力")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        capabilityAdminService.delete(id);
        return ApiResponse.ok();
    }
}
