package com.unicorn.studio.common.cache;

import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.mapper.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 浏览量计数（Redis 方案 §4.4，写热点剥离）。
 * <p>
 * 浏览时 {@code INCR cache:views:{type}:{id}} + {@code SADD cache:views:dirty}（活跃索引），
 * 定时任务逐 key {@code GETDEL}（读+清原子，Redis ≥ 6.2）后走**增量 UPDATE** 回写 MySQL。
 * 弱一致指标：Redis 异常一律静默降级（计数丢失/漏计可接受，绝不影响详情页返回）。
 */
@Component
public class ViewCountService {

    private static final Logger log = LoggerFactory.getLogger(ViewCountService.class);

    /** 脏索引兜底 TTL：每次 SADD 后续期，防异常 key 永久驻留。 */
    private static final Duration DIRTY_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final WorkMapper workMapper;
    private final NewsMapper newsMapper;

    public ViewCountService(StringRedisTemplate redis, WorkMapper workMapper, NewsMapper newsMapper) {
        this.redis = redis;
        this.workMapper = workMapper;
        this.newsMapper = newsMapper;
    }

    /** 记录一次浏览：INCR 计数 + SADD 脏索引；返回自增后的累计增量（0 = 降级）。 */
    public long increment(String type, Long id) {
        String key = CacheKeys.view(type, id);
        try {
            Long delta = redis.opsForValue().increment(key);
            redis.opsForSet().add(CacheKeys.VIEW_DIRTY, key);
            redis.expire(CacheKeys.VIEW_DIRTY, DIRTY_TTL);
            return delta == null ? 0 : delta;
        } catch (Exception e) {
            log.warn("浏览量计数降级 {}/{}: {}", type, id, e.getMessage());
            return 0;
        }
    }

    /**
     * 定时增量回写（默认每 2 min）：SMEMBERS 脏索引 → 逐 key GETDEL → 增量 UPDATE；
     * 回写失败重入脏索引下轮重试。弱一致指标，单条失败仅记日志不重抛。
     */
    @Scheduled(fixedDelayString = "${app.cache.view-flush-ms:120000}")
    public void flush() {
        Set<String> keys;
        try {
            keys = redis.opsForSet().members(CacheKeys.VIEW_DIRTY);
        } catch (Exception e) {
            log.warn("浏览量回写读取脏索引失败: {}", e.getMessage());
            return;
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            if (!key.startsWith(CacheKeys.VIEW_PREFIX)) {
                continue;
            }
            String raw;
            try {
                raw = redis.opsForValue().getAndDelete(key);
                redis.opsForSet().remove(CacheKeys.VIEW_DIRTY, key);
            } catch (Exception e) {
                log.warn("浏览量回写 GETDEL 失败 {}: {}", key, e.getMessage());
                continue;
            }
            if (raw == null) {
                continue;
            }
            long delta;
            try {
                delta = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                continue;
            }
            if (delta <= 0) {
                continue;
            }
            try {
                apply(key, delta);
            } catch (Exception e) {
                log.warn("浏览量回写失败 {}: {}", key, e.getMessage());
                reMarkDirty(key);
            }
        }
    }

    /** 按 key 类型路由到对应表做增量 UPDATE。key 形如 {@code cache:views:{type}:{id}}。 */
    private void apply(String key, long delta) {
        String[] parts = key.split(":");
        if (parts.length != 4) {
            return;
        }
        String type = parts[2];
        long id;
        try {
            id = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }
        if ("work".equals(type)) {
            workMapper.incrementViewCount(id, delta);
        } else if ("news".equals(type)) {
            newsMapper.incrementViewCount(id, delta);
        }
    }

    private void reMarkDirty(String key) {
        try {
            redis.opsForSet().add(CacheKeys.VIEW_DIRTY, key);
        } catch (Exception ignored) {
            // 再次失败则放弃，避免重试风暴；该 key 的增量靠下一轮浏览重新 SADD
        }
    }
}
