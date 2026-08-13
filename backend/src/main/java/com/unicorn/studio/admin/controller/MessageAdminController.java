package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.MessageAdminService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Message;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 留言管理（实施方案 §3.4）。 */
@RestController
@RequestMapping("/api/admin/messages")
public class MessageAdminController {

    private final MessageAdminService messageService;

    public MessageAdminController(MessageAdminService messageService) {
        this.messageService = messageService;
    }

    public record ReplyRequest(@NotBlank(message = "回复内容不能为空") String content) {
    }

    public record StatusRequest(@NotBlank(message = "状态不能为空") String status) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('message:list')")
    public ApiResponse<PageResult<Message>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(messageService.list(status, source, keyword, page - 1, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('message:list')")
    public ApiResponse<Message> detail(@PathVariable Long id) {
        return ApiResponse.ok(messageService.detail(id));
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('message:reply')")
    @AuditLog(module = "留言", action = "回复留言")
    public ApiResponse<Void> reply(@PathVariable Long id, @RequestBody ReplyRequest request) {
        messageService.reply(id, request.content(), SecurityUtils.currentUser().getId());
        return ApiResponse.ok();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('message:reply')")
    @AuditLog(module = "留言", action = "变更留言状态")
    public ApiResponse<Void> changeStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        messageService.changeStatus(id, request.status());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('message:delete')")
    @AuditLog(module = "留言", action = "删除留言")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        messageService.delete(id);
        return ApiResponse.ok();
    }
}
