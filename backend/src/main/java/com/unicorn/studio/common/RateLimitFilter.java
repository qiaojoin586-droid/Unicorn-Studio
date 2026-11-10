package com.unicorn.studio.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.common.cache.RedisRateLimiter;
import com.unicorn.studio.service.PublicAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 限流（Redis 方案 §4.5，原内存版 Bucket4j 已迁 Redis，见 {@link RedisRateLimiter}）：
 * - 按 路径模式 + IP 取计数桶（Redis {@code rate:{维度}:{ip}}，原子固定窗口 + 自动 TTL 淘汰）；
 * - 路径-策略映射集中在此（config 层），与业务代码解耦；
 * - 超限返回 429 + {code:42900}；Redis 不可用时 fail-open，生产另由 nginx limit_req 兜底。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final RedisRateLimiter rateLimiter;
    private final PublicAuthService publicAuthService;

    // ---- 限流阈值（可环境变量覆盖，默认见实施方案 §3.7） ----
    private final long loginPerMinute;
    private final long registerPerHour;
    private final long commentPerMinute;
    private final long guestCommentPerHour;
    private final long messagePerHour;
    private final long statsPerMinute;
    private final long aiPerMinute;

    public RateLimitFilter(ObjectMapper objectMapper,
                           RedisRateLimiter rateLimiter,
                           @Value("${app.ratelimit.login-per-minute:5}") long loginPerMinute,
                           @Value("${app.ratelimit.register-per-hour:10}") long registerPerHour,
                           @Value("${app.ratelimit.comment-per-minute:10}") long commentPerMinute,
                           @Value("${app.ratelimit.guest-comment-per-hour:10}") long guestCommentPerHour,
                           @Value("${app.ratelimit.message-per-hour:5}") long messagePerHour,
                           @Value("${app.ratelimit.stats-per-minute:60}") long statsPerMinute,
                           @Value("${app.ratelimit.ai-per-minute:5}") long aiPerMinute,
                           PublicAuthService publicAuthService) {
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.publicAuthService = publicAuthService;
        this.loginPerMinute = loginPerMinute;
        this.registerPerHour = registerPerHour;
        this.commentPerMinute = commentPerMinute;
        this.guestCommentPerHour = guestCommentPerHour;
        this.messagePerHour = messagePerHour;
        this.statsPerMinute = statsPerMinute;
        this.aiPerMinute = aiPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        String ip = IpUtils.getClientIp(request);

        if (isLimited(uri, method, ip, request)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            // Map.of 不允许 null 值，data 用 HashMap 承载
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("code", 42900);
            body.put("message", "操作过于频繁，请稍后再试");
            body.put("data", null);
            objectMapper.writeValue(response.getWriter(), body);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLimited(String uri, String method, String ip, HttpServletRequest request) {
        // 登录/刷新：IP 5 次/分
        if ("POST".equals(method) && ("/api/admin/auth/login".equals(uri) || "/api/admin/auth/refresh".equals(uri))) {
            return !rateLimiter.isAllowed(CacheKeys.rate("login", ip), loginPerMinute, Duration.ofMinutes(1));
        }
        // 前台注册：IP 10 次/时
        if ("POST".equals(method) && "/api/auth/register".equals(uri)) {
            return !rateLimiter.isAllowed(CacheKeys.rate("register", ip), registerPerHour, Duration.ofHours(1));
        }
        if ("POST".equals(method) && "/api/auth/register/code".equals(uri)) {
            return !rateLimiter.isAllowed(CacheKeys.rate("register-code", ip), registerPerHour, Duration.ofHours(1));
        }
        // 前台登录：IP 10 次/分
        if ("POST".equals(method) && "/api/auth/login".equals(uri)) {
            return !rateLimiter.isAllowed(CacheKeys.rate("auth-login", ip), 10, Duration.ofMinutes(1));
        }
        // 评论：IP 10 次/分；游客（无 Authorization 头）另限 10 条/时，两层独立
        if ("POST".equals(method) && "/api/comments".equals(uri)) {
            if (!rateLimiter.isAllowed(CacheKeys.rate("comment", ip), commentPerMinute, Duration.ofMinutes(1))) {
                return true;
            }
            String auth = request.getHeader("Authorization");
            if (auth == null || auth.isBlank()) {
                return !rateLimiter.isAllowed(CacheKeys.rate("guest-comment", ip), guestCommentPerHour, Duration.ofHours(1));
            }
            return false;
        }
        // 留言：IP 5 次/时
        if ("POST".equals(method) && "/api/messages".equals(uri)) {
            return !rateLimiter.isAllowed(CacheKeys.rate("message", ip), messagePerHour, Duration.ofHours(1));
        }
        // PV 埋点：IP 60 次/分
        if ("POST".equals(method) && "/api/stats/pv".equals(uri)) {
            return !rateLimiter.isAllowed(CacheKeys.rate("stats", ip), statsPerMinute, Duration.ofMinutes(1));
        }
        // AI 对话（文档 §9.4：双维度并存，任一超限即拦）
        // 主维度按用户：AI 已要求登录，只按 IP 限会让同一 NAT 出口的用户互相挤兑额度
        // 兜底维度按 IP：放宽到 3 倍，职责从"限成本"变成"挡批量注册的小号"
        if ("POST".equals(method) && "/api/ai/chat".equals(uri)) {
            Optional<Long> userId = publicAuthService.parseUserId(request.getHeader("Authorization"));
            if (userId.isPresent() && !rateLimiter.isAllowed(
                    CacheKeys.rate("ai:user", String.valueOf(userId.get())), aiPerMinute, Duration.ofMinutes(1))) {
                return true;
            }
            return !rateLimiter.isAllowed(CacheKeys.rate("ai", ip), aiPerMinute * 3, Duration.ofMinutes(1));
        }
        return false;
    }
}
