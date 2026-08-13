package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.AuthService;
import com.unicorn.studio.common.SecurityUtils;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.SysUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 后台认证接口（公开：登录/刷新；已登录：登出/me）。 */
@RestController
@RequestMapping("/api/admin/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout(SecurityUtils.currentUser());
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        SysUser user = SecurityUtils.currentUser();
        return ApiResponse.ok(authService.me(user));
    }
}
