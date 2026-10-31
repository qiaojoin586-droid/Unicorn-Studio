package com.unicorn.studio.AIWorker.controller;

import com.unicorn.studio.AIWorker.service.AiToolAdminService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.AIWorker.tool.ApiToolInvoker;
import com.unicorn.studio.AIWorker.vo.AdminToolVO;
import com.unicorn.studio.AIWorker.vo.ToolFormVO;
import com.unicorn.studio.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台工具配置（§6.5 接口 6~9）。
 * <p>
 * ⚠️ <b>三个权限码是分开的，不复用 {@code ai:edit}</b>（§12.3）：
 * <ul>
 *   <li>{@code ai:tool:view} —— 只读，EDITOR 也有；</li>
 *   <li>{@code ai:tool:edit} —— 填 URL = 决定后端向何处发请求，<b>仅 SUPER_ADMIN</b>；</li>
 *   <li>{@code ai:tool:use} —— 调用外部工具会消耗外部额度（可能计费）。</li>
 * </ul>
 * 能不能改与能不能调是两件事：一个 EDITOR 可以看，但不能改、不能调。
 */
@RestController
@RequestMapping("/api/admin/ai/tools")
public class AiToolAdminController {

    private final AiToolAdminService toolService;

    public AiToolAdminController(AiToolAdminService toolService) {
        this.toolService = toolService;
    }

    /** 接口 6：工具列表。 */
    @GetMapping
    @PreAuthorize("hasAuthority('ai:tool:view')")
    public ApiResponse<List<AdminToolVO>> list() {
        return ApiResponse.ok(toolService.listTools());
    }

    /** 接口 7（POST）：新建 API 工具。 */
    @PostMapping
    @AuditLog(module = "AI 工具", action = "新建 AI 工具")
    @PreAuthorize("hasAuthority('ai:tool:edit')")
    public ApiResponse<Long> create(@Valid @RequestBody ToolFormVO form) {
        return ApiResponse.ok(toolService.create(form));
    }

    /** 接口 7（PUT）：改 API 工具。 */
    @PutMapping("/{id}")
    @AuditLog(module = "AI 工具", action = "编辑 AI 工具")
    @PreAuthorize("hasAuthority('ai:tool:edit')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody ToolFormVO form) {
        toolService.update(id, form);
        return ApiResponse.ok();
    }

    /** 接口 7（DELETE）：删 API 工具。 */
    @DeleteMapping("/{id}")
    @AuditLog(module = "AI 工具", action = "删除 AI 工具")
    @PreAuthorize("hasAuthority('ai:tool:edit')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        toolService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 启用 / 停用工具（内置与 API 通用）。
     * <p>
     * ⚠️ 权限仍是 {@code ai:tool:edit}：停用一个工具 = 改变后端行为，
     * 风险等级与「填 URL」同级（§12.3）。
     */
    @PostMapping("/{id}/enabled")
    @AuditLog(module = "AI 工具", action = "启停 AI 工具")
    @PreAuthorize("hasAuthority('ai:tool:edit')")
    public ApiResponse<Void> toggleEnabled(@PathVariable Long id, @RequestBody EnabledForm form) {
        toolService.toggleEnabled(id, form.enabled());
        return ApiResponse.ok();
    }

    /** 启用开关的请求体（只带一个字段，够用且不容易传错）。 */
    public record EnabledForm(boolean enabled) {
    }

    /** 接口 8：确认当前指纹（描述变更后恢复可用）。 */
    @PostMapping("/{id}/confirm")
    @AuditLog(module = "AI 工具", action = "确认 AI 工具描述")
    @PreAuthorize("hasAuthority('ai:tool:edit')")
    public ApiResponse<Void> confirm(@PathVariable Long id) {
        toolService.confirm(id);
        return ApiResponse.ok();
    }

    /** 接口 9：连通性测试（只发一次探测请求）。 */
    @PostMapping("/{id}/test")
    @AuditLog(module = "AI 工具", action = "测试 AI 工具连通性")
    @PreAuthorize("hasAuthority('ai:tool:use')")
    public ApiResponse<ApiToolInvoker.ProbeResult> test(@PathVariable Long id) {
        return ApiResponse.ok(toolService.test(id));
    }
}
