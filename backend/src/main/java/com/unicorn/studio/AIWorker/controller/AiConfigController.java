package com.unicorn.studio.AIWorker.controller;

import com.unicorn.studio.AIWorker.dto.AiConfigUpdateRequest;
import com.unicorn.studio.AIWorker.dto.AiTestRequest;
import com.unicorn.studio.AIWorker.service.AiConfigService;
import com.unicorn.studio.AIWorker.service.AiConnectivityProbe;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.AIWorker.vo.AiConfigVO;
import com.unicorn.studio.AIWorker.vo.AiTestResultVO;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.UploadFile;
import com.unicorn.studio.admin.service.FileService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.async.DeferredResult;

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
    private final FileService fileService;
    private final AiConnectivityProbe connectivityProbe;

    public AiConfigController(AiConfigService configService, AiToolRegistry toolRegistry, FileService fileService,
                               AiConnectivityProbe connectivityProbe) {
        this.configService = configService;
        this.toolRegistry = toolRegistry;
        this.fileService = fileService;
        this.connectivityProbe = connectivityProbe;
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

    /** 上传消息头像，沿用 AI 编辑权限和现有图片校验、存储服务。 */
    @PostMapping("/message-avatar")
    @PreAuthorize("hasAuthority('ai:edit')")
    @AuditLog(module = "AI 配置", action = "上传助手消息头像")
    public ApiResponse<Map<String, Object>> uploadMessageAvatar(@RequestParam("file") MultipartFile file) {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024
                || name == null || !name.matches("(?i).+\\.(jpe?g|png|webp|gif)")) {
            throw BusinessException.badRequest("请上传不超过 5MB 的 JPG、PNG、WebP 或 GIF 图片");
        }
        UploadFile saved = fileService.upload(file, SecurityUtils.currentUser().getId());
        return ApiResponse.ok(Map.of("url", saved.getUrl(), "name", saved.getFileName(), "size", saved.getSize()));
    }

    /**
     * 测试连通性（一键验证所填配置是否可用）。
     * <p>
     * 支持「先测再存」：带上表单值即用表单值测试，未带则回退库中已保存配置。
     * 始终返回 200 + 结构化结果，前端据 {@code success} 渲染成功/失败态。
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('ai:test')")
    @AuditLog(module = "AI 配置", action = "发起 AI 连通性测试")
    public DeferredResult<ApiResponse<AiTestResultVO>> test(@RequestBody(required = false) AiTestRequest form) {
        return connectivityProbe.test(form);
    }
}
