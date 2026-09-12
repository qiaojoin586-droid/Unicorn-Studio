package com.unicorn.studio.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 限流（Redis 方案 §4.5 / §3.4 C3）。
 * <p>
 * 用「INCR + 首次写入时 EXPIRE」的**原子固定窗口**替代原内存 {@code ConcurrentHashMap + Bucket4j}，
 * 一举解决三个问题：① 内存泄漏（原桶只增不减，key=维度+IP 无上限）；② 多实例各自计数；
 * ③ 重启清零。Redis ≥ 2.6 即可（Lua 脚本），无需额外依赖。
 * <p>
 * 降级契约：Redis 任何异常一律**放行**（fail-open，记 warn）。理由：限流被绕过比「Redis 掉线 →
 * 接口 500 / 全站不可用」代价小得多，生产另有 nginx limit_req 兜底。
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** INCR + 首次写入时 EXPIRE（原子）；返回自增后的累计计数。 */
    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return c", Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 判定一次请求是否放行；窗口内计数 ≤ limit 即放行。Redis 异常时放行（fail-open）。 */
    public boolean isAllowed(String key, long limit, Duration window) {
        try {
            Long count = redis.execute(INCR_AND_EXPIRE, List.of(key),
                    String.valueOf(Math.max(1, window.getSeconds())));
            return count != null && count <= limit;
        } catch (Exception e) {
            log.warn("限流计数降级（放行）key={}: {}", key, e.getMessage());
            return true;
        }
    }
}
