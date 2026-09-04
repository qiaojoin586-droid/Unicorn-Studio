package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.ServiceItemAdminService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.ServiceItem;
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

/** 主要招收方向管理。 */
@RestController
@RequestMapping("/api/admin/services")
public class ServiceItemAdminController {

    private final ServiceItemAdminService serviceItemAdminService;

    public ServiceItemAdminController(ServiceItemAdminService serviceItemAdminService) {
        this.serviceItemAdminService = serviceItemAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('service:list')")
    public ApiResponse<PageResult<ServiceItemAdminService.ServiceItemView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 1) {
            throw BusinessException.badRequest("页码不能小于 1");
        }
        if (size < 1 || size > 100) {
            throw BusinessException.badRequest("每页数量必须在 1 到 100 之间");
        }
        return ApiResponse.ok(serviceItemAdminService.list(page - 1, size));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('service:create')")
    @AuditLog(module = "主要招收方向", action = "新增方向")
    public ApiResponse<Map<String, Object>> create(
            @Valid @RequestBody ServiceItemAdminService.ServiceItemForm form) {
        ServiceItem item = serviceItemAdminService.create(form);
        return ApiResponse.ok(Map.of("id", item.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('service:edit')")
    @AuditLog(module = "主要招收方向", action = "编辑方向")
    public ApiResponse<Void> update(
            @PathVariable Long id,
            @Valid @RequestBody ServiceItemAdminService.ServiceItemForm form) {
        serviceItemAdminService.update(id, form);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('service:delete')")
    @AuditLog(module = "主要招收方向", action = "删除方向")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        serviceItemAdminService.delete(id);
        return ApiResponse.ok();
    }
}
