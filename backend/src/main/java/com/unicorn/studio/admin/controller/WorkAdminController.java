package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.WorkAdminService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Work;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 作品管理（实施方案 §3.3）。 */
@RestController
@RequestMapping("/api/admin/works")
public class WorkAdminController {

    private final WorkAdminService workService;

    public WorkAdminController(WorkAdminService workService) {
        this.workService = workService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('work:list')")
    public ApiResponse<PageResult<Work>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(workService.list(keyword, category, status, page - 1, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('work:list')")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(workService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('work:create')")
    @AuditLog(module = "作品", action = "新增作品")
    public ApiResponse<Map<String, Object>> create(@RequestBody WorkAdminService.WorkForm form) {
        Work work = workService.create(form);
        return ApiResponse.ok(Map.of("id", work.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('work:edit')")
    @AuditLog(module = "作品", action = "编辑作品")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody WorkAdminService.WorkForm form) {
        workService.update(id, form);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('work:delete')")
    @AuditLog(module = "作品", action = "删除作品")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        workService.delete(id);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('work:publish')")
    @AuditLog(module = "作品", action = "上下架作品")
    public ApiResponse<Void> changeStatus(@PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        workService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    @PatchMapping("/sort")
    @PreAuthorize("hasAuthority('work:edit')")
    @AuditLog(module = "作品", action = "拖拽排序")
    public ApiResponse<Void> sort(@RequestBody SortRequest request) {
        workService.sort(request.ids());
        return ApiResponse.ok();
    }

    public record StatusRequest(@NotBlank(message = "状态不能为空") String status) {
    }

    public record SortRequest(List<Long> ids) {
    }
}
