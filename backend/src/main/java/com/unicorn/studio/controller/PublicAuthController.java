package com.unicorn.studio.controller;

import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.service.PublicAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台注册/登录（实施方案 §3.7，独立 token 体系）。 */
@RestController
@RequestMapping("/api/auth")
public class PublicAuthController {

    private final PublicAuthService publicAuthService;

    public PublicAuthController(PublicAuthService publicAuthService) {
        this.publicAuthService = publicAuthService;
    }

    public record RegisterRequest(@NotBlank(message = "昵称不能为空") String nickname,
                                  @NotBlank(message = "邮箱不能为空") String email,
                                  @NotBlank(message = "密码不能为空") String password) {
    }

    public record LoginRequest(@NotBlank(message = "邮箱不能为空") String email,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.ok(publicAuthService.register(
                request.nickname(), request.email(), request.password(),
                IpUtils.getClientIp(httpRequest)));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(publicAuthService.login(request.email(), request.password()));
    }
}
