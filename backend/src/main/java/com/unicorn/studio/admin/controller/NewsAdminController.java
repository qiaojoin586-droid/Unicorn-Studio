package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.NewsAdminService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.News;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 新闻管理（实施方案 §3.3，模式同作品）。 */
@RestController
@RequestMapping("/api/admin/news")
public class NewsAdminController {

    private final NewsAdminService newsService;

    public NewsAdminController(NewsAdminService newsService) {
        this.newsService = newsService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('news:list')")
    public ApiResponse<PageResult<News>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(newsService.list(keyword, category, status, page - 1, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('news:list')")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(newsService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('news:create')")
    @AuditLog(module = "新闻", action = "新增新闻")
    public ApiResponse<Map<String, Object>> create(@RequestBody NewsAdminService.NewsForm form) {
        News news = newsService.create(form, SecurityUtils.currentUser().getId());
        return ApiResponse.ok(Map.of("id", news.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('news:edit')")
    @AuditLog(module = "新闻", action = "编辑新闻")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody NewsAdminService.NewsForm form) {
        newsService.update(id, form);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('news:delete')")
    @AuditLog(module = "新闻", action = "删除新闻")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        newsService.delete(id);
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('news:publish')")
    @AuditLog(module = "新闻", action = "发布/下线新闻")
    public ApiResponse<Void> changeStatus(@PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        newsService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    @PatchMapping("/sort")
    @PreAuthorize("hasAuthority('news:edit')")
    @AuditLog(module = "新闻", action = "拖拽排序")
    public ApiResponse<Void> sort(@RequestBody SortRequest request) {
        newsService.sort(request.ids());
        return ApiResponse.ok();
    }

    public record StatusRequest(@NotBlank(message = "状态不能为空") String status) {
    }

    public record SortRequest(List<Long> ids) {
    }
}
