package com.unicorn.studio.common.cache;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 统一缓存访问模板（Redis 方案 §6.1，含降级）。
 * <p>
 * 口径（§5.5.1）：统一 {@link StringRedisTemplate}（key/value 均为 String），
 * value 由应用层用注入的 {@link ObjectMapper} 显式写出 JSON 字符串；反序列化由调用方显式给出目标类型。
 * <p>
 * 降级契约（硬性要求）：Redis 任何异常都**不抛出**，读返回 {@link Optional#empty()} 让调用方回源 MySQL、
 * 写/删仅记日志 ——「Redis 掉线 ≠ 接口 500」。
 * 失效统一走 {@link #evictAfterCommit}（事务提交后失效，规避「清完缓存又回滚」竞态）。
 */
@Component
public class CacheHelper {

    private static final Logger log = LoggerFactory.getLogger(CacheHelper.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheCircuitBreaker breaker;
    private final boolean enabled;
    private final double jitterRatio;

    public CacheHelper(StringRedisTemplate redis,
                       CacheCircuitBreaker breaker,
                       @Value("${app.cache.enabled:true}") boolean enabled,
                       @Value("${app.cache.jitter-ratio:0.10}") double jitterRatio) {
        this.redis = redis;
        // 缓存专用 ObjectMapper（§5.5.2）：JavaTimeModule + 关闭时间戳，独立于 MVC 的 ObjectMapper
        this.mapper = CacheObjectMapper.create();
        this.breaker = breaker;
        this.enabled = enabled;
        this.jitterRatio = jitterRatio;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 读缓存并反序列化为指定类型；未命中/降级返回 empty。 */
    public <T> Optional<T> get(String key, Class<T> type) {
        if (!enabled || breaker.isOpen()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            breaker.onSuccess();
            return json == null ? Optional.empty() : Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存读取降级 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 读缓存并按泛型类型反序列化（如 {@code Map<String,String>}）；未命中/降级返回 empty。 */
    public <T> Optional<T> get(String key, JavaType type) {
        if (!enabled || breaker.isOpen()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            breaker.onSuccess();
            return json == null ? Optional.empty() : Optional.ofNullable(mapper.readValue(json, type));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存读取降级 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 读原始字符串 value（如浏览量计数等非 JSON 值）；未命中/降级返回 empty。 */
    public Optional<String> getString(String key) {
        if (!enabled || breaker.isOpen()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(key);
            breaker.onSuccess();
            return Optional.ofNullable(value);
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存读取降级 key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** 写缓存（对象 → JSON）；失败仅记日志，绝不影响业务返回值。 */
    public void set(String key, Object value, Duration ttl) {
        if (!enabled || breaker.isOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), jitter(ttl));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存写入降级 key={}: {}", key, e.getMessage());
        }
    }

    /** 写原始字符串 value（带 TTL）。 */
    public void setString(String key, String value, Duration ttl) {
        if (!enabled || breaker.isOpen()) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, jitter(ttl));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存写入降级 key={}: {}", key, e.getMessage());
        }
    }

    /** 删除一个或多个 key；失败仅记日志。 */
    public void delete(String... keys) {
        if (!enabled || keys == null || keys.length == 0) {
            return;
        }
        try {
            redis.delete(List.of(keys));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存删除降级 keys={}: {}", Arrays.toString(keys), e.getMessage());
        }
    }

    /** 按模式删除（SCAN + 游标，不用 KEYS，避免阻塞 Redis，§5.1）；失败仅记日志。 */
    public void deleteByPattern(String pattern) {
        if (!enabled || pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                cursor.forEachRemaining(keys::add);
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存按模式删除降级 pattern={}: {}", pattern, e.getMessage());
        }
    }

    /**
     * 事务提交后失效，避免「清完缓存又回滚」与「回填旧值」竞态（§5.3 陷阱 1）。
     * 无活动事务时立即失效。
     */
    public void evictAfterCommit(String... keys) {
        afterCommit(() -> delete(keys));
    }

    /** 按模式失效（事务提交后执行）。 */
    public void evictByPatternAfterCommit(String pattern) {
        afterCommit(() -> deleteByPattern(pattern));
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** TTL 随机抖动（§4.3 A1）：在 [base*(1-r), base*(1+r)] 内取值，避免同批 key 同时过期。 */
    private Duration jitter(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || jitterRatio <= 0) {
            return ttl;
        }
        double factor = 1.0 - jitterRatio + ThreadLocalRandom.current().nextDouble() * 2.0 * jitterRatio;
        return Duration.ofMillis(Math.max(1, (long) (ttl.toMillis() * factor)));
    }
}
