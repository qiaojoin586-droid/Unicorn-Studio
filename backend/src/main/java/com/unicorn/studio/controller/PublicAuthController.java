package com.unicorn.studio.controller;

import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.service.PublicAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
                                  @NotBlank(message = "手机号不能为空") String phone,
                                  @NotBlank(message = "密码不能为空") String password,
                                  @NotBlank(message = "验证码不能为空") String code) {
    }

    public record LoginRequest(@NotBlank(message = "手机号不能为空") String phone,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record RegistrationCodeRequest(@NotBlank(message = "手机号不能为空") String phone) {
    }

    @PostMapping("/register/code")
    public ApiResponse<Map<String, Integer>> sendRegistrationCode(@Valid @RequestBody RegistrationCodeRequest request) {
        var sent = publicAuthService.sendRegistrationCode(request.phone());
        return ApiResponse.ok(Map.of("retryAfterSeconds", sent.retryAfterSeconds(),
                "remainingHourlySends", sent.remainingHourlySends()));
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.ok(publicAuthService.register(
                request.nickname(), request.phone(), request.password(), request.code(),
                IpUtils.getClientIp(httpRequest)));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(publicAuthService.login(request.phone(), request.password()));
    }

    /**
     * 退出登录：撤销当前 token（记入 Redis 撤销名单）。
     * <p>
     * 参数从 Authorization 头取而不是从 SecurityContext 取：前台没有
     * {@code SecurityUtils.currentUser()}（它只返回后台 {@code SysUser}），
     * 且本接口要能在 token 已失效时也能安全调用（幂等，见 {@code PublicAuthService#logout}）。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        publicAuthService.logout(authorization);
        return ApiResponse.ok();
    }
}
