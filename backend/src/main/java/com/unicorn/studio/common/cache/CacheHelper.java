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
import java.util.*;
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
    private final CacheCircuitBreaker breaker;// 缓存熔断器
    private final boolean enabled;// 缓存是否启用
    private final double jitterRatio;// TTL 随机抖动比例

    /**
     * 键前缀：{@code app.cache.namespace} + 当前 profile（形如 {@code local:} / {@code prod:}）。
     * <p>
     * <b>为什么必须有它（2026-09-22 实测故障）</b>：本地开发与线上服务器<b>共用同一台 Redis 的
     * 同一个 db</b>（见 {@code application-local.yml} 与 {@code REDIS_HOST}），而缓存键里
     * 没有任何环境标识 —— 于是两边互相读写对方的缓存：
     * <ol>
     *   <li>本地库只有 4 条学习资料，但本地实例的列表接口返回了 <b>7 条</b>；
     *       顺着查到 {@code cache:learning:list} 里存的正是<b>服务器环境</b>写入的那 7 条；</li>
     *   <li>那 7 条带着服务器的 {@code coverUrl} / {@code attachmentUrl}
     *       （{@code /uploads/2026/09/8bb46a63…png}），而对应文件只存在于服务器磁盘 ——
     *       本地或线上任何一方渲染对方的数据，图片与附件就必然 404；</li>
     *   <li>更麻烦的是 TTL 让它「像是有延迟」：写方环境一直在续期，读方以为缓存过期后会回源，
     *       实际每次命中的都是对方的旧数据 —— 这正是「经过一段时间后自动 404」的真实机理。</li>
     * </ol>
     * 前缀让 `local:` / `prod:` 两套键彻底分开：即使共用一台 Redis 同一个 db，也不会互相污染。
     * 历史上已写入的无前缀键不会被再读取（自然过期即可），故本次改动无需手工清理。
     */
    private final String keyPrefix;

    public CacheHelper(StringRedisTemplate redis,
                       CacheCircuitBreaker breaker,
                       @Value("${app.cache.enabled:true}") boolean enabled,
                       @Value("${app.cache.jitter-ratio:0.10}") double jitterRatio,
                       @Value("${app.cache.namespace:}") String namespace,
                       @Value("${spring.profiles.active:default}") String profiles) {
        this.redis = redis;
        // 缓存专用 ObjectMapper（§5.5.2）：JavaTimeModule + 关闭时间戳，独立于 MVC 的 ObjectMapper
        this.mapper = CacheObjectMapper.create();
        this.breaker = breaker;
        this.enabled = enabled;
        this.jitterRatio = jitterRatio;
        this.keyPrefix = buildPrefix(namespace, profiles);
        log.info("缓存键环境隔离前缀: '{}'（可用 app.cache.namespace 覆盖；空前缀=与旧版本行为一致）", keyPrefix);
    }

    /**
     * 组装前缀，形如 {@code unicorn:prod:}。
     * <p>
     * 用「命名空间 + 生效 profile」而不是只靠运维记得改 {@code spring.data.redis.database}：
     * 配置会被漏配、会被复制粘贴，而<b>代码里的隔离不依赖任何人的记性</b>。
     * 多 profile 同时激活（如 {@code local,dev}）时取第一个，保证同一套配置下前缀稳定。
     */
    private static String buildPrefix(String namespace, String profiles) {
        String ns = namespace == null ? "" : namespace.trim();
        String profile = profiles == null ? "" : profiles.trim();
        int comma = profile.indexOf(',');
        if (comma >= 0) {
            profile = profile.substring(0, comma).trim();
        }
        if (profile.isEmpty()) {
            profile = "default";
        }
        StringBuilder sb = new StringBuilder();
        if (!ns.isEmpty()) {
            sb.append(ns).append(':');
        }
        return sb.append(profile).append(':').toString();
    }

    /** 加前缀。分隔符统一用 {@code :}，与既有键命名规范一致。 */
    private String k(String key) {
        return keyPrefix + key;
    }

    /**
     * 暴露加过环境前缀的完整键名，供<b>需要自己拼键</b>的组件复用（当前只有 {@code RedisRateLimiter}）。
     * <p>
     * 限流器有自己独立的 {@code StringRedisTemplate}、不经过本类，若让它自己拼前缀就得把
     * {@link #buildPrefix} 的逻辑复制一份——而「同一份前缀逻辑写两处」正是 §3.3 踩过的坑
     * （本地与线上共用一台 Redis，缺前缀会互相消耗额度且全程不报错）。
     */
    public String prefixed(String key) {
        return k(key);
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
            String json = redis.opsForValue().get(k(key));
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
            String json = redis.opsForValue().get(k(key));
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
            String value = redis.opsForValue().get(k(key));
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
            redis.opsForValue().set(k(key), mapper.writeValueAsString(value), jitter(ttl));
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
            redis.opsForValue().set(k(key), value, jitter(ttl));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存写入降级 key={}: {}", key, e.getMessage());
        }
    }

    /** SETNX 短锁（供预热等多实例互斥使用）：同样带环境前缀，避免跨环境互相顶掉锁。 */
    public boolean setIfAbsent(String key, Duration ttl) {
        if (!enabled || breaker.isOpen()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k(key), "1", ttl));
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存加锁降级 key={}: {}", key, e.getMessage());
            return false;
        }
    }

    /** 删除一个或多个 key；失败仅记日志。 */
    public void delete(String... keys) {
        if (!enabled || keys == null || keys.length == 0) {
            return;
        }
        try {
            redis.delete(java.util.Arrays.stream(keys).map(this::k).toList());
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存删除降级 keys={}: {}", Arrays.toString(keys), e.getMessage());
        }
    }

    /** 按模式删除（SCAN + 游标，不用 KEYS，避免阻塞 Redis，§5.1）；失败仅记日志。 */
    /** 按模式删除时的单批大小（B-26）：边扫描边分批删，避免把所有命中 key 攒在内存里 */
    private static final int DELETE_BATCH_SIZE = 500;

    /**
     * 按模式删除缓存键。
     * <p>
     * <b>边扫描边分批删（B-26）</b>：原实现把所有命中 key 累积进 {@code ArrayList} 后才一次
     * {@code DEL}。当前调用方只有 {@code cache:auth:perm:*} 与 {@code cache:site:group:*}
     * （量小，安全），但 pattern 一旦扩散（例如误用 {@code cache:*}）就会变成无界内存操作 ——
     * 而 {@code SCAN} 存在的意义恰恰是「不让一次操作把内存吃掉」，
     * 先累积再删等于把这个好处又丢了回去。
     * <p>
     * 每 {@link #DELETE_BATCH_SIZE} 个删一批：内存占用与命中 key 总量脱钩。
     */
    public void deleteByPattern(String pattern) {
        if (!enabled || pattern == null || pattern.isBlank()) {
            return;
        }
        // 前缀在同一处拼装（调用方传的是 CacheKeys 的裸键模式，如 cache:auth:perm:*），
        // 保证「按模式删」与「按 key 删/写」命中的是同一片键空间 —— 只加在一处才不会漏。
        String prefixed = k(pattern);
        try {
            List<String> batch = new ArrayList<>(DELETE_BATCH_SIZE);
            ScanOptions options = ScanOptions.scanOptions().match(prefixed).count(100).build();
            try (Cursor<String> cursor = redis.scan(options)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= DELETE_BATCH_SIZE) {
                        redis.delete(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
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

    /** 缓存计数器自增,用来实现单位时间内限流等场景
     * 未命中/降级返回空 Optional(包装可能为 null 的值，避免空指针)
     * */
    public Optional<Long> increment(String key) {
        if(!enabled || breaker.isOpen()){
            // 未命中/降级返回空 Optional
            return Optional.empty();
        }
        try {
            //
            Long value = redis.opsForValue().increment(k(key));
            breaker.onSuccess();
            // 返回 Optional.ofNullable(value), 如果 value 为 null，则返回 Optional.empty()，否则返回包住 value 的 Optional
            return Optional.ofNullable(value);
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("缓存计数器自增降级 key={} : {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void expireAt(String key, Date deadline){
        if(!enabled || breaker.isOpen()){
            return;
        }
        try {
            // 设置过期时间,到时间key自动删除
            redis.expireAt(k(key), deadline);
        } catch (Exception e) {
            log.warn("缓存设置过期时间降级 key={}: {}", key, e.getMessage());
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
