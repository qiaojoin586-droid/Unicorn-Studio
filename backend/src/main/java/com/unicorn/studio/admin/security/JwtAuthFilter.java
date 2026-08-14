package com.unicorn.studio.admin.security;

import com.unicorn.studio.admin.service.AdminPermissionService;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.model.SysUser;
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
 * /api/admin/** 认证过滤器：Bearer token → 查库加载角色/权限（实时，角色修改即时生效）→
 * 注入 Authentication。SUPER_ADMIN 加载全量权限码（代码级绕过，注解写法不变）。
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                Long userId = Long.valueOf(claims.getSubject());
                SysUser user = sysUserMapper.selectById(userId);
                if (user != null && user.getStatus() != null && user.getStatus() == 1) {
                    AdminPermissionService.LoadResult result = permissionService.loadForUser(user);
                    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                    result.roles().forEach(role ->
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getCode())));
                    result.permissions().forEach(code ->
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
    }
}
