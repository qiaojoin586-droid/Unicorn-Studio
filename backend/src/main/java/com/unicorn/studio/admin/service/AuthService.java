package com.unicorn.studio.admin.service;

import com.unicorn.studio.admin.security.JwtService;
import com.unicorn.studio.admin.security.LoginLockService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 后台认证：登录（账号锁定持久化）/ refresh 轮换 / 登出 / me。
 * 密码错误统一"用户名或密码错误"，不暴露账号是否存在。
 */
@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginLockService loginLockService;
    private final AdminPermissionService permissionService;
    private final long refreshExpireDays;

    public AuthService(SysUserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginLockService loginLockService,
                       AdminPermissionService permissionService,
                       @Value("${app.admin.refresh-expire-days:7}") long refreshExpireDays) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginLockService = loginLockService;
        this.permissionService = permissionService;
        this.refreshExpireDays = refreshExpireDays;
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        SysUser user = userMapper.findByUsername(username)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "账号已禁用，请联系管理员");
        }
        loginLockService.checkLocked(user);

        if (!passwordEncoder.matches(password, user.getPassword())) {
            loginLockService.onFailure(user);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        // onSuccess 内部为全列 updateById，先写入 lastLoginAt 可省一次重复 UPDATE
        user.setLastLoginAt(LocalDateTime.now());
        loginLockService.onSuccess(user);
        return tokenPair(user);
    }

    /** refresh 轮换：旧 token 匹配且未过期 → 生成新 UUID 覆盖，旧值立即失效。 */
    @Transactional
    public Map<String, Object> refresh(String refreshToken) {
        SysUser user = userMapper.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"));
        if (user.getRefreshTokenExpiresAt() == null
                || !user.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "账号已禁用");
        }
        return tokenPair(user);
    }

    @Transactional
    public void logout(SysUser user) {
        user.setRefreshToken(null);
        user.setRefreshTokenExpiresAt(null);
        userMapper.updateById(user);
    }

    /** /auth/me：用户信息 + 角色 + 权限码（前端动态路由与按钮级控制的数据源）。 */
    public Map<String, Object> me(SysUser user) {
        AdminPermissionService.LoadResult load = permissionService.loadForUser(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("mustChangePassword", user.getMustChangePassword());
        result.put("roles", load.roles().stream()
                .map(r -> Map.of("code", r.getCode(), "name", r.getName()))
                .toList());
        result.put("permissions", load.permissions());
        return result;
    }

    private Map<String, Object> tokenPair(SysUser user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = UUID.randomUUID().toString();
        user.setRefreshToken(refreshToken);
        user.setRefreshTokenExpiresAt(LocalDateTime.now().plusDays(refreshExpireDays));
        userMapper.updateById(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("expiresIn", jwtService.getExpireSeconds());
        result.put("mustChangePassword", user.getMustChangePassword());
        return result;
    }
}
