package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.LearningAdminService;
import com.unicorn.studio.admin.service.LearningCategoryService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.LearningCategory;
import com.unicorn.studio.model.LearningResource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 学习资料管理（资料 CRUD/上下架/批量删除 + 分类管理）。 */
@RestController
@RequestMapping("/api/admin/learning")
public class LearningAdminController {

    private final LearningAdminService learningService;
    private final LearningCategoryService categoryService;

    public LearningAdminController(LearningAdminService learningService,
                                   LearningCategoryService categoryService) {
        this.learningService = learningService;
        this.categoryService = categoryService;
    }

    public record StatusRequest(@NotNull(message = "状态不能为空") Integer status) {
    }

    public record BatchDeleteRequest(@NotEmpty(message = "ids 不能为空") List<Long> ids) {
    }

    public record CategoryForm(@NotBlank(message = "分类名称不能为空") String name,
                               String description, Integer sortOrder, Integer status) {
    }

    // ---- 资料 ----

    @GetMapping("/resources")
    @PreAuthorize("hasAuthority('learning:list')")
    public ApiResponse<PageResult<LearningResource>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(learningService.list(category, status, keyword, page - 1, size));
    }

    @PostMapping("/resources")
    @PreAuthorize("hasAuthority('learning:create')")
    @AuditLog(module = "学习资料", action = "新增资料")
    public ApiResponse<Map<String, Object>> create(@RequestBody LearningAdminService.LearningForm form) {
        LearningResource resource = learningService.create(form);
        return ApiResponse.ok(Map.of("id", resource.getId()));
    }

    @PutMapping("/resources/{id}")
    @PreAuthorize("hasAuthority('learning:edit')")
    @AuditLog(module = "学习资料", action = "编辑资料")
    public ApiResponse<Void> update(@PathVariable Long id,
                                    @RequestBody LearningAdminService.LearningForm form) {
        learningService.update(id, form);
        return ApiResponse.ok();
    }

    @PatchMapping("/resources/{id}/status")
    @PreAuthorize("hasAuthority('learning:publish')")
    @AuditLog(module = "学习资料", action = "上下架资料")
    public ApiResponse<Void> changeStatus(@PathVariable Long id,
                                          @Valid @RequestBody StatusRequest request) {
        learningService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/resources/{id}")
    @PreAuthorize("hasAuthority('learning:delete')")
    @AuditLog(module = "学习资料", action = "删除资料")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        learningService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/resources/batch-delete")
    @PreAuthorize("hasAuthority('learning:delete')")
    @AuditLog(module = "学习资料", action = "批量删除资料")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody BatchDeleteRequest request) {
        return ApiResponse.ok(learningService.batchDelete(request.ids()));
    }

    // ---- 分类 ----

    /** 分类列表（含各分类资料数）；资料表单的下拉数据源，故门槛为 learning:list。 */
    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('learning:list')")
    public ApiResponse<List<LearningCategoryService.CategoryItem>> categories() {
        return ApiResponse.ok(categoryService.listAll());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('learning:category')")
    @AuditLog(module = "资料分类", action = "新增分类")
    public ApiResponse<Map<String, Object>> createCategory(@Valid @RequestBody CategoryForm form) {
        LearningCategory category = categoryService.create(form.name(), form.description(),
                form.sortOrder(), form.status());
        return ApiResponse.ok(Map.of("id", category.getId()));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('learning:category')")
    @AuditLog(module = "资料分类", action = "编辑分类")
    public ApiResponse<Void> updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryForm form) {
        categoryService.update(id, form.name(), form.description(), form.sortOrder(), form.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('learning:category')")
    @AuditLog(module = "资料分类", action = "删除分类")
    public ApiResponse<Void> deleteCategory(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.ok();
    }
}
