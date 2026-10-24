package com.unicorn.studio.AIWorker.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具熔断（文档 §11.5「连续 3 次失败 → 熔断 5 分钟」，R18）。
 * <p>
 * <b>为什么不能直接用 {@code CacheCircuitBreaker}</b>：那个类阈值读的是
 * {@code app.cache.circuit-breaker.failure-threshold}（默认 10 次 / 30s），
 * 而工具要求 <b>3 次 / 5 分钟</b>。共用实例意味着「为保护工具把缓存熔断也调成 3 次」——
 * 两个不相干的功能被一个配置项绑死。§11.5 说的是复用它的<b>模式</b>，不是它的实例。
 * <p>
 * <b>状态按工具名分桶</b>：一个工具挂了不该让其他工具也停摆，而缓存熔断是全局一个计数器。
 * 工具数量受「内置 ≤15 / API ≤5」约束，map 不会无界增长。
 */
@Component
public class ToolCircuitBreaker {

    private final int failureThreshold;
    private final long openDurationMillis;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ToolCircuitBreaker(
            @Value("${app.ai.tools.circuit-breaker.failure-threshold:3}") int failureThreshold,
            @Value("${app.ai.tools.circuit-breaker.open-duration:5m}") Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMillis = openDuration.toMillis();
    }

    /** 该工具当前是否熔断（装配时应跳过它）。 */
    public boolean isOpen(String toolName) {
        State s = states.get(toolName);
        if (s == null) {
            return false;
        }
        long openUntil = s.openUntilMillis;
        if (openUntil == 0) {
            return false;
        }
        if (System.currentTimeMillis() < openUntil) {
            return true;
        }
        // 打开期已过 → 半开：清零放行一次探测（best-effort，与 CacheCircuitBreaker 同一口径）
        s.openUntilMillis = 0;
        s.consecutiveFailures.set(0);
        return false;
    }

    /** 熔断剩余秒数；0 = 未熔断。供后台列表显示（§11.5「熔断可见」）。 */
    public long remainingSeconds(String toolName) {
        State s = states.get(toolName);
        if (s == null || s.openUntilMillis == 0) {
            return 0;
        }
        long left = s.openUntilMillis - System.currentTimeMillis();
        // 向上取整，例如 1499ms = 2s，1500ms = 3s，保证剩余时间至少为 1s
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** 该工具成功一次，重置失败计数。 */
    public void onSuccess(String toolName) {
        states.computeIfAbsent(toolName, k -> new State()).consecutiveFailures.set(0);
    }

    /** 该工具失败一次，增加失败计数。 */
    public void onFailure(String toolName) {
        State s = states.computeIfAbsent(toolName, k -> new State());
        if (s.consecutiveFailures.incrementAndGet() >= failureThreshold) {
            s.openUntilMillis = System.currentTimeMillis() + openDurationMillis;
        }
    }

    /** 单工具的熔断状态。字段需 volatile / 原子类：多线程会并发调用同一工具。 */
    private static final class State {
        /** 连续失败次数，达到阈值后进入打开期。 */
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        /** 熔断打开时，请求放行的时间点（0 = 关闭）。 */
        private volatile long openUntilMillis = 0;
    }
}
