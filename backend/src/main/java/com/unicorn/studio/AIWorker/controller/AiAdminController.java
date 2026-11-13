package com.unicorn.studio.AIWorker.controller;

import com.unicorn.studio.AIWorker.service.AiAdminService;
import com.unicorn.studio.AIWorker.service.AiHistoryCleanupService;
import com.unicorn.studio.AIWorker.vo.AdminMessageVO;
import com.unicorn.studio.AIWorker.vo.AdminSessionVO;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 会话后台管理（文档 §6.5 接口 1~5）。
 * <p>
 * 挂在 {@code /api/admin/**} 下复用后台 JWT 过滤器链（AdminSecurityConfig @Order(1)），
 * 权限码复用已有的 ai:view / retention:run（V16、V14 已播种，不新造）。
 * <p>
 * 读接口不套 {@code @AuditLog}：接口 2、3 的读取审计在 Service 里显式写（§9.2）。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AiAdminController {

    private final AiAdminService adminService;
    private final AiHistoryCleanupService historyCleanupService;

    /**
     * 获取会话列表。
     */
    @GetMapping("/conversations")
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<PageResult<AdminSessionVO>> conversations(@RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "10") int size,
                                                                 @RequestParam(required = false) Long userId,
                                                                 @RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to,
                                                                 @RequestParam(defaultValue = "true") Boolean includeDeleted){

        return ApiResponse.ok(adminService.findAllConversationsAdmin(
                userId, from, to, includeDeleted, page-1/*前端页码从1开始，后端页码从0开始**/, size));
    }

    /**
     * 获取会话消息列表。
     */
    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<PageResult<AdminMessageVO>> conversationMessages(@PathVariable String id,
                                                                       @RequestParam(defaultValue = "1") int page,
                                                                       @RequestParam(defaultValue = "20") int size){
        return ApiResponse.ok(adminService.findAllMessagesAdmin(id, page-1, size));
    }

    /**
     * 跨会话检索消息。
     */
    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<PageResult<AdminMessageVO>> messages(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) Long userId,
                                                            @RequestParam(required = false) String conversationId,
                                                            @RequestParam(required = false) String from,
                                                            @RequestParam(required = false) String to,
                                                            @RequestParam(required = false) String status){
        return ApiResponse.ok(adminService.searchAdmin(userId, conversationId, from, to, status, page-1, size));
    }

    /**
     * 获取历史清理任务状态。
     */
    @GetMapping("/cleanup/status")
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<AiHistoryCleanupService.CleanupStatus> cleanupStatus() {
        return ApiResponse.ok(historyCleanupService.status());
    }

    /**
     * 手动触发清理任务。
     */
    @PostMapping("/cleanup")
    @PreAuthorize("hasAuthority('retention:run')")
    @AuditLog(module = "AI历史数据清理", action = "手动触发清理ai历史")
    public ApiResponse<AiHistoryCleanupService.CleanupRunReport> cleanup(
            @RequestParam(defaultValue = "false") boolean dryRun){
        return ApiResponse.ok(historyCleanupService.cleanup(dryRun, "manual"));
    }

}
