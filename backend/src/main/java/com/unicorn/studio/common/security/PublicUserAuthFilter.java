package com.unicorn.studio.common.security;

import com.unicorn.studio.model.PublicUser;
import com.unicorn.studio.service.PublicAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 前台身份过滤器：把 {@code Authorization} 头里的前台 token 解析成 {@code SecurityContext} 中的身份，
 * 让 {@code /api/**} 上的接口能统一拿到「当前站点用户」，不必各自解析 header。
 * <p>
 * 只负责「认人」，不负责「拦人」——解析不出来就不设身份、照常放行（保持游客可浏览的现状）。
 * 「哪些接口必须登录」由 {@link RequireSiteUser} 在方法级决定。
 * <p>
 * 注册见 {@code SecurityConfig#publicChain}，必须排在 {@code RateLimitFilter} 之后：
 * 先限流挡住超频请求，再花代价查库认人。
 */
@Component
public class PublicUserAuthFilter extends OncePerRequestFilter {

    private final PublicAuthService publicAuthService;

    public PublicUserAuthFilter(PublicAuthService publicAuthService) {
        this.publicAuthService = publicAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 传整个头原文（含 "Bearer " 前缀），前缀由 resolveFromHeader 自己剥。
        // 无 token / 无效 / 过期 / 账号不可用都返回 null —— 内部已吞掉 JwtException，故这里不需要 try/catch。
        PublicUser user = publicAuthService.resolveFromHeader(request.getHeader("Authorization"));
        if (user != null) {
            // 三参数构造器才会把 authenticated 置为 true；authorities 给空集合，前台没有权限码体系。
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, List.of()));
        }
        // 认不出来也放行，否则 /api/** 上大量匿名接口会全站 401
        filterChain.doFilter(request, response);
    }
}
