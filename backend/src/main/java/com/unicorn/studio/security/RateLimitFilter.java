package com.unicorn.studio.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RateLimitFilter {

    private final int maxAttempts;
    private final long windowMs;
    private final Set<String> trustedProxies;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.security.login-rate-limit-max:10}") int maxAttempts,
            @Value("${app.security.login-rate-limit-window-ms:600000}") long windowMs,
            @Value("${app.security.trusted-proxies:}") String trustedProxies) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.trustedProxies = parseTrustedProxies(trustedProxies);
    }

    public boolean isLimited(HttpServletRequest request) {
        AttemptState state = snapshot(getClientIp(request), System.currentTimeMillis());
        return state != null && state.count >= maxAttempts;
    }

    public void recordFailure(HttpServletRequest request) {
        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        attempts.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.firstAttemptAt > windowMs) {
                return new AttemptState(1, now);
            }
            // 不可变替换，避免并发读读到被原地修改的中间状态
            return new AttemptState(existing.count + 1, existing.firstAttemptAt);
        });
    }

    public void clearFailure(HttpServletRequest request) {
        String ip = getClientIp(request);
        attempts.remove(ip);
    }

    public long getRetryAfterSeconds(HttpServletRequest request) {
        AttemptState state = snapshot(getClientIp(request), System.currentTimeMillis());
        if (state == null) return 0;
        long remaining = windowMs - (System.currentTimeMillis() - state.firstAttemptAt);
        return Math.max(1, (long) Math.ceil(remaining / 1000.0));
    }

    /**
     * 原子地读取某个 IP 的限流状态，同时惰性清除已过窗口期的过期条目，
     * 防止 map 无限增长。
     */
    private AttemptState snapshot(String ip, long now) {
        AtomicReference<AttemptState> ref = new AtomicReference<>();
        attempts.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.firstAttemptAt > windowMs) {
                return null; // 移除过期条目
            }
            ref.set(existing);
            return existing;
        });
        return ref.get();
    }

    private String getClientIp(HttpServletRequest request) {
        // 仅当直连对端是受信代理时才信任 X-Forwarded-For，
        // 否则攻击者可伪造该头绕过限流
        String peer = request.getRemoteAddr();
        if (trustedProxies.contains(peer)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return peer;
    }

    private static Set<String> parseTrustedProxies(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(value.split(",")));
    }

    private static final class AttemptState {
        final int count;
        final long firstAttemptAt;

        AttemptState(int count, long firstAttemptAt) {
            this.count = count;
            this.firstAttemptAt = firstAttemptAt;
        }
    }
}
