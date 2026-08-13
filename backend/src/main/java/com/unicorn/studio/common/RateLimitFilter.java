package com.unicorn.studio.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bucket4j 内存限流（实施方案 §5.9）：
 * - 通用桶引擎：按 路径模式 + IP 取桶（ConcurrentHashMap 惰性创建）；
 * - 路径-策略映射集中在此（config 层），与业务代码解耦；
 * - 超限返回 429 + {code:42900}；生产另由 nginx limit_req 兜底。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    // ---- 限流阈值（可环境变量覆盖，默认见实施方案 §3.7） ----
    private final long loginPerMinute;
    private final long registerPerHour;
    private final long commentPerMinute;
    private final long guestCommentPerHour;
    private final long messagePerHour;
    private final long statsPerMinute;

    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${app.ratelimit.login-per-minute:5}") long loginPerMinute,
                           @Value("${app.ratelimit.register-per-hour:10}") long registerPerHour,
                           @Value("${app.ratelimit.comment-per-minute:10}") long commentPerMinute,
                           @Value("${app.ratelimit.guest-comment-per-hour:10}") long guestCommentPerHour,
                           @Value("${app.ratelimit.message-per-hour:5}") long messagePerHour,
                           @Value("${app.ratelimit.stats-per-minute:60}") long statsPerMinute) {
        this.objectMapper = objectMapper;
        this.loginPerMinute = loginPerMinute;
        this.registerPerHour = registerPerHour;
        this.commentPerMinute = commentPerMinute;
        this.guestCommentPerHour = guestCommentPerHour;
        this.messagePerHour = messagePerHour;
        this.statsPerMinute = statsPerMinute;
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
            return !bucket("login:" + ip, loginPerMinute, Duration.ofMinutes(1)).tryConsume(1);
        }
        // 前台注册：IP 10 次/时
        if ("POST".equals(method) && "/api/auth/register".equals(uri)) {
            return !bucket("register:" + ip, registerPerHour, Duration.ofHours(1)).tryConsume(1);
        }
        // 前台登录：IP 10 次/分
        if ("POST".equals(method) && "/api/auth/login".equals(uri)) {
            return !bucket("auth-login:" + ip, 10, Duration.ofMinutes(1)).tryConsume(1);
        }
        // 评论：IP 10 次/分；游客（无 Authorization 头）另限 10 条/时，两层独立
        if ("POST".equals(method) && "/api/comments".equals(uri)) {
            if (!bucket("comment:" + ip, commentPerMinute, Duration.ofMinutes(1)).tryConsume(1)) {
                return true;
            }
            String auth = request.getHeader("Authorization");
            if (auth == null || auth.isBlank()) {
                return !bucket("guest-comment:" + ip, guestCommentPerHour, Duration.ofHours(1)).tryConsume(1);
            }
            return false;
        }
        // 留言：IP 5 次/时
        if ("POST".equals(method) && "/api/messages".equals(uri)) {
            return !bucket("message:" + ip, messagePerHour, Duration.ofHours(1)).tryConsume(1);
        }
        // PV 埋点：IP 60 次/分
        if ("POST".equals(method) && "/api/stats/pv".equals(uri)) {
            return !bucket("stats:" + ip, statsPerMinute, Duration.ofMinutes(1)).tryConsume(1);
        }
        return false;
    }

    private Bucket bucket(String key, long capacity, Duration period) {
        return buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, period)
                        .build())
                .build());
    }
}
