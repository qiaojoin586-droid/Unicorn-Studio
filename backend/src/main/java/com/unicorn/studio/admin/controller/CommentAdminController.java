package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.CommentAdminService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 评论审核管理（实施方案 §3.4）。 */
@RestController
@RequestMapping("/api/admin/comments")
public class CommentAdminController {

    private final CommentAdminService commentService;

    public CommentAdminController(CommentAdminService commentService) {
        this.commentService = commentService;
    }

    public record StatusRequest(@NotBlank(message = "状态不能为空") String status) {
    }

    public record ReplyRequest(@NotBlank(message = "回复内容不能为空") String content) {
    }

    public record BatchRequest(@NotEmpty(message = "ids 不能为空") List<Long> ids,
                               @NotBlank(message = "操作不能为空") String action) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('comment:list')")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(commentService.list(status, targetType, keyword, page - 1, size));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('comment:audit')")
    @AuditLog(module = "评论", action = "审核评论")
    public ApiResponse<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        commentService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('comment:reply')")
    @AuditLog(module = "评论", action = "回复评论")
    public ApiResponse<Map<String, Object>> reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.ok(Map.of("id", commentService.reply(id, request.content()).getId()));
    }

    @PatchMapping("/batch")
    @PreAuthorize("hasAuthority('comment:audit')")
    @AuditLog(module = "评论", action = "批量操作")
    public ApiResponse<Map<String, Object>> batch(@Valid @RequestBody BatchRequest request) {
        return ApiResponse.ok(commentService.batch(request.ids(), request.action()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('comment:delete')")
    @AuditLog(module = "评论", action = "删除评论")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return ApiResponse.ok();
    }
}
