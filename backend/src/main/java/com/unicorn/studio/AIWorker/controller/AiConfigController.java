package com.unicorn.studio.AIWorker.controller;

import com.unicorn.studio.AIWorker.dto.AiConfigUpdateRequest;
import com.unicorn.studio.AIWorker.dto.AiTestRequest;
import com.unicorn.studio.AIWorker.service.AiConfigService;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.AIWorker.vo.AiConfigVO;
import com.unicorn.studio.AIWorker.vo.AiTestResultVO;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台 AI 配置管理。
 * <p>
 * 挂在 {@code /api/admin/**} 下复用后台 JWT 过滤器链（AdminSecurityConfig @Order(1)），
 * 权限码 ai:view / ai:edit / ai:test（见 V16 迁移）。
 * <p>
 * <b>安全约定</b>：
 * <ul>
 *   <li>GET 只返回脱敏 key（完整 key 不出后端）；</li>
 *   <li>PUT 落库前加密（服务层负责），审计留痕但<b>不记录 key 内容</b>；</li>
 *   <li>测试连通性为只读探测（不写库），但仍受权限与审计约束 —— 它会消耗真实 API 额度。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/ai-config")
public class AiConfigController {

    private final AiConfigService configService;
    private final AiToolRegistry toolRegistry;

    public AiConfigController(AiConfigService configService, AiToolRegistry toolRegistry) {
        this.configService = configService;
        this.toolRegistry = toolRegistry;
    }

    /** 读取当前配置（API Key 脱敏）。 */
    @GetMapping
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<AiConfigVO> get() {
        return ApiResponse.ok(configService.current());
    }

    /** 已注册的工具列表（工具层扩展位的可视化，便于后续排查）。 */
    @GetMapping("/tools")
    @PreAuthorize("hasAuthority('ai:view')")
    public ApiResponse<Map<String, Object>> tools() {
        return ApiResponse.ok(Map.of(
                "registered", toolRegistry.registeredNames(),
                "count", toolRegistry.registeredNames().size()));
    }

    /** 保存配置（API Key 加密存储）。 */
    @PutMapping
    @PreAuthorize("hasAuthority('ai:edit')")
    @AuditLog(module = "AI 配置", action = "更新 AI 配置")
    public ApiResponse<Void> update(@RequestBody AiConfigUpdateRequest form) {
        configService.save(form);
        return ApiResponse.ok();
    }

    /**
     * 测试连通性（一键验证所填配置是否可用）。
     * <p>
     * 支持「先测再存」：带上表单值即用表单值测试，未带则回退库中已保存配置。
     * 始终返回 200 + 结构化结果，前端据 {@code success} 渲染成功/失败态。
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('ai:test')")
    @AuditLog(module = "AI 配置", action = "测试 AI 连通性")
    public ApiResponse<AiTestResultVO> test(@RequestBody(required = false) AiTestRequest form) {
        return ApiResponse.ok(configService.test(form));
    }
}
