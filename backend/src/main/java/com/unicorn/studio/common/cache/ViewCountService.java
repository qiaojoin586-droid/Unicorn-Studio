package com.unicorn.studio.common.cache;

import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.mapper.WorkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

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
    private final CacheCircuitBreaker breaker;

    public ViewCountService(StringRedisTemplate redis, WorkMapper workMapper, NewsMapper newsMapper,
                            CacheCircuitBreaker breaker) {
        this.redis = redis;
        this.workMapper = workMapper;
        this.newsMapper = newsMapper;
        this.breaker = breaker;
    }

    /**
     * 记录一次浏览：INCR 计数 + SADD 脏索引；返回自增后的累计增量（0 = 降级）。
     * <p>
     * <b>接入熔断器（B-30③）</b>：这是全站最热的 Redis 路径 —— <b>每次作品/新闻详情浏览</b>
     * 都会走一次（详情页是本站访问量最大的页面）。Redis 宕机时原先每次浏览都要白等
     * 一次连接超时，而浏览量是「弱一致指标，丢了也无所谓」——
     * 为一个可丢弃的计数让每个详情页请求都慢几百毫秒，代价与收益完全不成比例。
     */
    public long increment(String type, Long id) {
        String key = CacheKeys.view(type, id);
        if (breaker.isOpen()) {
            return 0;
        }
        try {
            Long delta = redis.opsForValue().increment(key);
            redis.opsForSet().add(CacheKeys.VIEW_DIRTY, key);
            redis.expire(CacheKeys.VIEW_DIRTY, DIRTY_TTL);
            breaker.onSuccess();
            return delta == null ? 0 : delta;
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("浏览量计数降级 {}/{}: {}", type, id, e.getMessage());
            return 0;
        }
    }

    /**
     * 「取走增量 + 移出脏索引」合并为一次往返（B-16）。
     * <p>
     * 原实现是 {@code GETDEL} 再 {@code SREM} 两次独立往返，N 个 key 就是 2N 次 RTT。
     * 两者合并为 Lua 后降为 N 次，且「取值」与「移出索引」真正原子 ——
     * 分开执行时若中间失败，会出现「索引里还有、但计数已被取走」的状态，
     * 下一轮取到 nil 才自然清理掉。
     * <p>
     * 无论 key 是否存在都 SREM：GETDEL 返回 nil 说明该计数 key 已过期/被删，
     * 脏索引里的条目已是陈旧项，正该清掉。
     */
    private static final DefaultRedisScript<String> DRAIN_AND_UNMARK = new DefaultRedisScript<>(
            "local v = redis.call('GETDEL', KEYS[2]) " +
            "redis.call('SREM', KEYS[1], KEYS[2]) " +
            "return v", String.class);

    /** SSCAN 每批返回的成员数（B-16）：只影响往返次数，不影响单批内存上界 */
    private static final int SCAN_BATCH = 500;

    /**
     * 定时增量回写（默认每 2 min）：SSCAN 脏索引 → 逐 key 取增量 → 增量 UPDATE；
     * 回写失败重入脏索引下轮重试。弱一致指标，单条失败仅记日志不重抛。
     * <p>
     * <b>SSCAN 取代 SMEMBERS（B-16）</b>：脏索引的成员数 = 本轮被浏览过的不同作品/新闻数，
     * 且每个被浏览过的 id 会常驻 7 天（每次 increment 都续期）。被爬虫批量扫详情页时
     * 这个 Set 能涨到数万条 —— SMEMBERS 会把它们一次性全拉进堆。
     * SSCAN 用游标分批（每批 500），把「无界读」变成「有界流式处理」：
     * 总工作量不变，但内存占用与集合大小脱钩。
     */
    @Scheduled(fixedDelayString = "${app.cache.view-flush-ms:120000}")
    public void flush() {
        ScanOptions options = ScanOptions.scanOptions().count(SCAN_BATCH).build();
        try (Cursor<String> cursor = redis.opsForSet().scan(CacheKeys.VIEW_DIRTY, options)) {
            while (cursor.hasNext()) {
                flushOne(cursor.next());
            }
        } catch (Exception e) {
            // 游标迭代中途失败：已处理的部分已回写，剩余的下轮再扫（脏索引未被清空）
            log.warn("浏览量回写扫描脏索引失败: {}", e.getMessage());
        }
    }

    private void flushOne(String key) {
        if (key == null || !key.startsWith(CacheKeys.VIEW_PREFIX)) {
            return;
        }
        String raw;
        try {
            raw = redis.execute(DRAIN_AND_UNMARK, List.of(CacheKeys.VIEW_DIRTY, key));
        } catch (Exception e) {
            log.warn("浏览量回写取增量失败 {}: {}", key, e.getMessage());
            return;
        }
        // raw 可以为 null：Lua 里 GETDEL 未命中时返回 false，Spring 会把它映射成 null。
        // （IDE 可能把这里标成 dead code —— 那是误报，脚本 API 被标注为非空而已。
        //  这条分支是必要的：计数 key 已过期、只剩脏索引条目时就会走到。）
        if (raw == null) {
            return;
        }
        long delta;
        try {
            delta = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return;
        }
        if (delta <= 0) {
            return;
        }
        try {
            apply(key, delta);
        } catch (Exception e) {
            log.warn("浏览量回写失败 {}: {}", key, e.getMessage());
            reMarkDirty(key);
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
