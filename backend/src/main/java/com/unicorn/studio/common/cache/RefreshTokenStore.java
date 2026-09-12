package com.unicorn.studio.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 后台刷新令牌的 Redis 权威存储（Redis 方案 §4.6 / §3.4 C4）。
 * <p>
 * 令牌本就是 key-value，迁到 Redis 后：① 消灭 {@code findByRefreshToken} 的
 * {@code sys_user.refresh_token} 无索引全表扫描；② 天然获得 TTL（与
 * {@code app.admin.refresh-expire-days} 对齐）。DB 的 {@code refresh_token} 列保留为
 * 历史残留，**不再读写**（Redis 是唯一权威）。
 * <p>
 * 键：{@code cache:refresh:{token}} → userId（EX 7d）；
 * 另维护 {@code cache:user:tokens:{userId}} 为 SET 索引，用于登出/改密/禁用/删除时批量清理。
 * <p>
 * 降级契约：Redis 不可用时 {@code find} 返回 empty（refresh 拒绝 → 用户重新登录，比读到过期
 * 令牌更安全，§4.6 点 4「可接受」）；{@code save/delete} 仅记日志不抛出。
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 保存令牌：SET cache:refresh:{token} = userId（TTL） + SADD 用户令牌索引（索引同 TTL 续期）。 */
    public void save(String token, Long userId, Duration ttl) {
        try {
            String tokenKey = CacheKeys.refresh(token);
            String setKey = CacheKeys.userTokens(userId);
            redis.opsForValue().set(tokenKey, String.valueOf(userId), ttl);
            redis.opsForSet().add(setKey, token);
            redis.expire(setKey, ttl);
        } catch (Exception e) {
            log.warn("刷新令牌写入降级 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 查询令牌归属：GET cache:refresh:{token} → userId；未命中/降级返回 empty。 */
    public Optional<Long> find(String token) {
        try {
            String value = redis.opsForValue().get(CacheKeys.refresh(token));
            if (value == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(value));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("刷新令牌读取降级: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 删除单个令牌（轮换时让旧令牌立即失效）。 */
    public void delete(String token, Long userId) {
        try {
            redis.delete(List.of(CacheKeys.refresh(token)));
            redis.opsForSet().remove(CacheKeys.userTokens(userId), token);
        } catch (Exception e) {
            log.warn("刷新令牌删除降级 userId={}: {}", userId, e.getMessage());
        }
    }

    /** 删除某用户全部令牌（登出 / 改密 / 禁用 / 删除账号时批量清理）。 */
    public void deleteAllForUser(Long userId) {
        try {
            String setKey = CacheKeys.userTokens(userId);
            Set<String> tokens = redis.opsForSet().members(setKey);
            if (tokens != null && !tokens.isEmpty()) {
                List<String> tokenKeys = tokens.stream().map(CacheKeys::refresh).toList();
                redis.delete(tokenKeys);
            }
            redis.delete(setKey);
        } catch (Exception e) {
            log.warn("刷新令牌批量清理降级 userId={}: {}", userId, e.getMessage());
        }
    }
}
