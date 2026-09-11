package com.unicorn.studio.common.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存熔断器（Redis 方案 §4.3 A4 / §6.1）。
 * <p>
 * 连续失败达到阈值后「打开」一段时间，期间直接跳过 Redis（返回未命中，由调用方回源 MySQL），
 * 避免 Redis 不可达时每个请求都被拖住；打开期结束自动「半开」放行一次探测。
 * 仅用于缓存读写的降级兜底，不承载任何业务语义。
 */
@Component
public class CacheCircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMillis;

    /** 连续失败计数 */
    private final AtomicLong consecutiveFailures = new AtomicLong(0);
    /** 熔断打开截止时间（0 = 关闭）；volatile 保证跨线程可见 */
    private volatile long openUntilMillis = 0;

    public CacheCircuitBreaker(@Value("${app.cache.circuit-breaker.failure-threshold:10}") int failureThreshold,
                               @Value("${app.cache.circuit-breaker.open-duration:30s}") Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMillis = openDuration.toMillis();
    }

    /** 是否处于熔断打开状态（调用方应跳过 Redis）。 */
    public boolean isOpen() {
        long openUntil = openUntilMillis;
        if (openUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() < openUntil) {
            return true;
        }
        // 打开期已过 → 半开：放行下一次探测（best-effort，不追求强一致）
        openUntilMillis = 0;
        consecutiveFailures.set(0);
        return false;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilMillis = System.currentTimeMillis() + openDurationMillis;
        }
    }

    /** 观测用：当前连续失败数（§9.1 降级告警，健康值必须为 0）。 */
    public long failureCount() {
        return consecutiveFailures.get();
    }
}
