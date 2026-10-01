package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.PresentationService;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.PresentationVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Static routes keep section permissions separate and cannot write arbitrary settings. */
@RestController
@RequestMapping("/api/admin/presentation")
public class PresentationAdminController {
    private final PresentationService presentation;
    public PresentationAdminController(PresentationService presentation) { this.presentation = presentation; }

    /** Preview uses the same sanitizer as the save path and never writes content. */
    @PostMapping("/preview-html")
    @PreAuthorize("hasAnyAuthority('work:list','work:create','work:edit','learning:list','learning:create','learning:edit')")
    public ApiResponse<PreviewHtml> previewHtml(@RequestBody(required=false) PreviewHtml form) {
        String html = form == null || form.html() == null ? "" : form.html();
        if (html.length() > HtmlSanitizer.MAX_CONTENT_LENGTH) {
            throw BusinessException.badRequest("正文长度超过上限（" + HtmlSanitizer.MAX_CONTENT_LENGTH + " 字符），当前 " + html.length());
        }
        return ApiResponse.ok(new PreviewHtml(html.isBlank() ? "" : HtmlSanitizer.clean(html)));
    }
    public record PreviewHtml(String html) { }

    @GetMapping("/home") @PreAuthorize("hasAuthority('carousel:list')")
    public ApiResponse<PresentationVO.Home> home() { return ApiResponse.ok(presentation.loadHome()); }
    @PutMapping("/home") @PreAuthorize("hasAuthority('carousel:edit')")
    @AuditLog(module="首页配置", action="更新展示配置")
    public ApiResponse<PresentationVO.Home> updateHome(@RequestBody PresentationVO.Home form) { return ApiResponse.ok(presentation.saveHome(form)); }
    @GetMapping("/about") @PreAuthorize("hasAuthority('about:view')")
    public ApiResponse<PresentationVO.About> about() { return ApiResponse.ok(presentation.loadAbout()); }
    @PutMapping("/about") @PreAuthorize("hasAuthority('about:edit')")
    @AuditLog(module="关于我们", action="更新展示配置")
    public ApiResponse<PresentationVO.About> updateAbout(@RequestBody PresentationVO.About form) { return ApiResponse.ok(presentation.saveAbout(form)); }
    @GetMapping("/footer") @PreAuthorize("hasAuthority('footer:view')")
    public ApiResponse<PresentationVO.Footer> footer() { return ApiResponse.ok(presentation.loadFooter()); }
    @PutMapping("/footer") @PreAuthorize("hasAuthority('footer:edit')")
    @AuditLog(module="页脚设置", action="更新展示配置")
    public ApiResponse<PresentationVO.Footer> updateFooter(@RequestBody PresentationVO.Footer form) { return ApiResponse.ok(presentation.saveFooter(form)); }
    @GetMapping("/join") @PreAuthorize("hasAuthority('join:view')")
    public ApiResponse<PresentationVO.Join> join() { return ApiResponse.ok(presentation.loadJoin()); }
    @PutMapping("/join") @PreAuthorize("hasAuthority('join:edit')")
    @AuditLog(module="加入我们", action="更新展示配置")
    public ApiResponse<PresentationVO.Join> updateJoin(@RequestBody PresentationVO.Join form) { return ApiResponse.ok(presentation.saveJoin(form)); }
}
