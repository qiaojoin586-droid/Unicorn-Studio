package com.unicorn.studio.controller;

import com.unicorn.studio.admin.service.FooterService;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.FooterVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台页脚配置（V13）。
 * <p>
 * 与「关于我们」一致：这是结构化配置而非原始 KV，故用专用接口返回（并已做默认值兜底），
 * 不额外暴露到 /api/site/settings。
 */
@RestController
@RequestMapping("/api/footer")
public class FooterController {

    private final FooterService footerService;

    public FooterController(FooterService footerService) {
        this.footerService = footerService;
    }

    @GetMapping
    public ApiResponse<FooterVO> get() {
        return ApiResponse.ok(footerService.load());
    }
}
