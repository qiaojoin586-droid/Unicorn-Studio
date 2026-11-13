package com.unicorn.studio.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 前台 token 撤销名单（与 {@link RefreshTokenStore} 同构）。
 * <p>
 * <b>为什么需要它</b>：前台是纯无状态 JWT（{@code PublicAuthService.tokenOf()} 只签
 * subject/iat/exp，服务端不存任何东西）⇒ 「删本地 token」并不等于撤销，被复制的 token
 * 在有效期内照样能用。后台的 logout 能真撤销是因为有 {@code cache:refresh:{token}} 可删，
 * 前台没有对应的东西，只能自己维护一份名单。
 * <p>
 * 键：{@code cache:public:revoked:{sha256(token)}} → {@code 1}，
 * <b>TTL 取 token 的剩余有效期</b>（exp 减当前时间）—— token 本身到期后名单条目自动清掉，
 * 不需要额外的清理任务，名单长度天然被「活跃 token 数」封顶。
 * <p>
 * 降级契约（与 {@code RefreshTokenStore} 相反，这里是<b>失败开放</b>）：未命中 / Redis 不可用
 * 一律视为「未撤销」放行。反过来做（失败即视为已撤销）会让 Redis 一次抖动把<b>全站已登录用户</b>
 * 踢下线；名单只影响「主动退出登录」这一个低频动作，短暂失效可接受。
 */
@Component
public class RevokedTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RevokedTokenStore.class);

    private final StringRedisTemplate redis;
    private final CacheCircuitBreaker breaker;

    public RevokedTokenStore(StringRedisTemplate redis, CacheCircuitBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    /**
     * 撤销一个 token，TTL = 剩余有效期。
     * <p>
     * <b>写路径不接熔断</b>（同 {@code RefreshTokenStore#delete} 的理由）：熔断打开时 Redis
     * 可能已经恢复，此时跳过写入会让本该失效的 token 继续可用 —— 这是安全相关动作，
     * 宁可多等一次连接超时也不能不写。
     */
    public void revoke(String tokenHash, Duration ttl) {
        if (tokenHash == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redis.opsForValue().set(CacheKeys.revokedPublic(tokenHash), "1", ttl);
        } catch (Exception e) {
            log.warn("前台 token 撤销写入降级: {}", e.getMessage());
        }
    }

    /** 是否已撤销；未命中 / 熔断打开 / 降级一律返回 false（失败开放，见类注释）。 */
    public boolean isRevoked(String tokenHash) {
        if (tokenHash == null) {
            return false;
        }
        if (breaker.isOpen()) {
            return false;
        }
        try {
            Boolean hit = redis.hasKey(CacheKeys.revokedPublic(tokenHash));
            breaker.onSuccess();
            return Boolean.TRUE.equals(hit);
        } catch (Exception e) {
            breaker.onFailure();
            log.warn("前台撤销名单读取降级: {}", e.getMessage());
            return false;
        }
    }
}
