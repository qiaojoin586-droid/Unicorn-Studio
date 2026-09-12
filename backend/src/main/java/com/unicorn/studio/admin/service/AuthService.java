package com.unicorn.studio.admin.service;

import com.unicorn.studio.admin.security.JwtService;
import com.unicorn.studio.admin.security.LoginLockService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.RefreshTokenStore;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
    private final RefreshTokenStore refreshTokenStore;
    private final long refreshExpireDays;

    public AuthService(SysUserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginLockService loginLockService,
                       AdminPermissionService permissionService,
                       RefreshTokenStore refreshTokenStore,
                       @Value("${app.admin.refresh-expire-days:7}") long refreshExpireDays) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginLockService = loginLockService;
        this.permissionService = permissionService;
        this.refreshTokenStore = refreshTokenStore;
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

    /**
     * refresh 轮换：Redis 权威令牌（§4.6）。
     * 旧 token 命中 → 按 userId 加载用户 → 校验状态 → 生成新令牌（写入 Redis）→ 删除旧令牌立即失效。
     * Redis 令牌 TTL 即过期时间，无需再比对 refresh_token_expires_at。
     */
    @Transactional
    public Map<String, Object> refresh(String refreshToken) {
        Long userId = refreshTokenStore.find(refreshToken)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"));
        SysUser user = Optional.ofNullable(userMapper.selectById(userId))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"));
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "账号已禁用");
        }
        Map<String, Object> result = tokenPair(user);
        // 新令牌已写入；旧令牌立即失效（轮换）
        refreshTokenStore.delete(refreshToken, user.getId());
        return result;
    }

    @Transactional
    public void logout(SysUser user) {
        // Redis 为唯一权威：清掉该用户所有刷新令牌（多端同时下线）
        refreshTokenStore.deleteAllForUser(user.getId());
    }

    /** /auth/me：用户信息 + 角色 + 权限码（前端动态路由与按钮级控制的数据源）。权限复用 Redis 缓存快照。 */
    public Map<String, Object> me(SysUser user) {
        com.unicorn.studio.vo.AuthPermissionsVO perms = permissionService.loadPermissionsCached(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("mustChangePassword", user.getMustChangePassword());
        result.put("roles", perms.roles().stream()
                .map(r -> Map.of("code", r.code(), "name", r.name()))
                .toList());
        result.put("permissions", perms.permissions());
        return result;
    }

    private Map<String, Object> tokenPair(SysUser user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = UUID.randomUUID().toString();
        // Redis 权威存储（§4.6）：不再写 sys_user.refresh_token / refresh_token_expires_at
        refreshTokenStore.save(refreshToken, user.getId(), Duration.ofDays(refreshExpireDays));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("expiresIn", jwtService.getExpireSeconds());
        result.put("mustChangePassword", user.getMustChangePassword());
        return result;
    }
}
