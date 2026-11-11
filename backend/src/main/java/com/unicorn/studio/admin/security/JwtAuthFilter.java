package com.unicorn.studio.admin.security;

import com.unicorn.studio.admin.service.AdminPermissionService;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.vo.AuthPermissionsVO;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * /api/admin/** 认证过滤器：Bearer token → 实时校验账号状态 → 加载角色/权限（Redis 缓存，§6.3）→
 * 注入 Authentication。账号状态每次 {@code selectById} 实时校验（禁用/删除即时 401，无越权窗口）；
 * 权限走 {@link AdminPermissionService#loadPermissionsCached}（TTL 10 min，写路径覆盖失效）。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;
    private final AdminPermissionService permissionService;

    public JwtAuthFilter(JwtService jwtService,
                         SysUserMapper sysUserMapper,
                         AdminPermissionService permissionService) {
        this.jwtService = jwtService;
        this.sysUserMapper = sysUserMapper;
        this.permissionService = permissionService;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // DeferredResult 完成后会再次派发；无状态认证需根据原请求的 Bearer 重新建立上下文。
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.parse(header.substring(7));
                    Long userId = Long.valueOf(claims.getSubject());
                    // 账号状态实时查库校验（不缓存），禁用/删除即时生效（R4 安全边界）
                    SysUser user = sysUserMapper.selectById(userId);
                    if (user != null && user.getStatus() != null && user.getStatus() == 1) {
                        AuthPermissionsVO perms = permissionService.loadPermissionsCached(user);
                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        perms.roles().forEach(role ->
                                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.code())));
                        perms.permissions().forEach(code ->
                                authorities.add(new SimpleGrantedAuthority(code)));

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(user, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                    // 用户不存在/已禁用：不设置认证 → 401（由 admin 链 entry point 处理）
                } catch (JwtException | IllegalArgumentException e) {
                    // 无效/过期 token：不设置认证，链上入口点返回 401
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 清理请求级权限缓存，禁止跨请求复用（安全边界，§2.5 P-1）
            permissionService.clearRequestCache();
        }
    }
}
