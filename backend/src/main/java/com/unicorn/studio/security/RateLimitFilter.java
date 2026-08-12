package com.unicorn.studio.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter {

    private final int maxAttempts;
    private final long windowMs;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public RateLimitFilter(
            org.springframework.beans.factory.annotation.Value("${app.security.login-rate-limit-max:10}") int maxAttempts,
            org.springframework.beans.factory.annotation.Value("${app.security.login-rate-limit-window-ms:600000}") long windowMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
    }

    public boolean isLimited(HttpServletRequest request) {
        String ip = getClientIp(request);
        AttemptState state = attempts.get(ip);
        long now = System.currentTimeMillis();

        if (state == null || now - state.firstAttemptAt > windowMs) {
            return false;
        }

        return state.count >= maxAttempts;
    }

    public void recordFailure(HttpServletRequest request) {
        String ip = getClientIp(request);
        long now = System.currentTimeMillis();

        attempts.compute(ip, (key, existing) -> {
            if (existing == null || now - existing.firstAttemptAt > windowMs) {
                return new AttemptState(1, now);
            }
            existing.count++;
            return existing;
        });
    }

    public void clearFailure(HttpServletRequest request) {
        String ip = getClientIp(request);
        attempts.remove(ip);
    }

    public long getRetryAfterSeconds(HttpServletRequest request) {
        String ip = getClientIp(request);
        AttemptState state = attempts.get(ip);
        if (state == null) return 0;
        long remaining = windowMs - (System.currentTimeMillis() - state.firstAttemptAt);
        return Math.max(1, (long) Math.ceil(remaining / 1000.0));
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static class AttemptState {
        int count;
        long firstAttemptAt;

        AttemptState(int count, long firstAttemptAt) {
            this.count = count;
            this.firstAttemptAt = firstAttemptAt;
        }
    }
}
