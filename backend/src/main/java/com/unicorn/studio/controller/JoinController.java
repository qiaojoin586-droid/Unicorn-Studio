package com.unicorn.studio.controller;

import com.unicorn.studio.admin.service.JoinPageService;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.JoinPageVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台「加入我们」页面内容（V17）。
 * <p>
 * 与「关于我们」一致：这里是结构化文案而非原始 KV，故用专用接口返回（并已做默认值兜底），
 * 不额外暴露到 /api/site/settings。
 */
@RestController
@RequestMapping("/api/join")
public class JoinController {

    private final JoinPageService joinPageService;

    public JoinController(JoinPageService joinPageService) {
        this.joinPageService = joinPageService;
    }

    @GetMapping
    public ApiResponse<JoinPageVO> get() {
        return ApiResponse.ok(joinPageService.load());
    }
}
