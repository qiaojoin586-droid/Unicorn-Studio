package com.unicorn.studio.controller;

import com.unicorn.studio.admin.service.AboutPageService;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.AboutPageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台「关于我们」页面内容（V12）。
 * <p>
 * 与「轮播图/页面背景」不同，这里是结构化文案而非原始 KV，故用专用接口返回（并已做默认值兜底），
 * 不额外暴露到 /api/site/settings。
 */
@RestController
@RequestMapping("/api/about")
public class AboutController {

    private final AboutPageService aboutPageService;

    public AboutController(AboutPageService aboutPageService) {
        this.aboutPageService = aboutPageService;
    }

    @GetMapping
    public ApiResponse<AboutPageVO> get() {
        return ApiResponse.ok(aboutPageService.load());
    }
}
