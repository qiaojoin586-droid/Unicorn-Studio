package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class RegistrationCodeService {
    public static final int RESEND_SECONDS = 60;
    public static final int VALID_SECONDS = 300;
    public static final int HOURLY_LIMIT = 10;
    private static final int HOUR_SECONDS = 3600;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(RegistrationCodeService.class);

    // 优先用 Redis 时间统计最近一小时的发送，旧版本回退应用时间；预占额度、冷却和验证码原子写入。
    // 返回 {状态（0=冷却、1=放行、2=小时额度已满）, 重试秒数, 剩余额度}。
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ISSUE = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[8])
            if redis.replicate_commands then
                redis.replicate_commands()
                local clock = redis.call('TIME')
                now = clock[1] * 1000 + math.floor(clock[2] / 1000)
            end
            local window = tonumber(ARGV[6]) * 1000
            local limit = tonumber(ARGV[7])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - window)
            local count = redis.call('ZCARD', KEYS[3])
            local function quotaWait()
                local oldest = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
                return math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
            end
            if count >= limit then return {2, quotaWait(), 0} end
            local cooldown = redis.call('PTTL', KEYS[2])
            if cooldown > 0 then return {0, math.max(1, math.ceil(cooldown / 1000)), limit - count} end
            redis.call('SET', KEYS[2], ARGV[5], 'EX', ARGV[1])
            redis.call('HMSET', KEYS[1], 'code', ARGV[2], 'attempts', ARGV[3])
            redis.call('HSET', KEYS[1], 'delivery', ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('ZADD', KEYS[3], now, ARGV[5])
            redis.call('PEXPIRE', KEYS[3], window)
            local wait = tonumber(ARGV[1])
            if count + 1 >= limit then wait = math.max(wait, quotaWait()) end
            return {1, wait, limit - count - 1}
            """, List.class);

    // 校验成功即消费，输错只扣次数，不延长有效期。
    private static final DefaultRedisScript<Long> VERIFY = new DefaultRedisScript<>("""
            local code = redis.call('HGET', KEYS[1], 'code')
            if not code then return 0 end
            if code == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            end
            if redis.call('HINCRBY', KEYS[1], 'attempts', -1) <= 0 then
                redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    // 仅撤销本次明确被供应商拒绝的发送，不能误删后续请求生成的新验证码。
    private static final DefaultRedisScript<Long> REVOKE_REJECTED = new DefaultRedisScript<>("""
            local removed = redis.call('ZREM', KEYS[3], ARGV[1])
            if redis.call('ZCARD', KEYS[3]) == 0 then redis.call('DEL', KEYS[3]) end
            if redis.call('HGET', KEYS[1], 'delivery') == ARGV[1] then
                redis.call('DEL', KEYS[1], KEYS[2])
            end
            return removed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CacheHelper cache;
    private final Optional<RegistrationSmsSender> sender;

    public RegistrationCodeService(StringRedisTemplate redis, CacheHelper cache,
                                   Optional<RegistrationSmsSender> sender) {
        this.redis = redis;
        this.cache = cache;
        this.sender = sender;
    }

    public record SendResult(int retryAfterSeconds, int remainingHourlySends) {}

    public SendResult send(String phone) {
        RegistrationSmsSender delivery = sender.orElseThrow(() -> unavailable("注册验证码服务尚未启用"));
        try {
            delivery.checkAvailable();
        } catch (RuntimeException e) {
            throw unavailable("注册验证码服务尚未配置完成，请联系管理员");
        }
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String key = codeKey(phone);
        String deliveryId = UUID.randomUUID().toString();
        List<?> issued = execute(ISSUE, deliveryKeys(key), Integer.toString(RESEND_SECONDS), code,
                Integer.toString(MAX_ATTEMPTS), Integer.toString(VALID_SECONDS), deliveryId,
                Integer.toString(HOUR_SECONDS), Integer.toString(HOURLY_LIMIT), Long.toString(System.currentTimeMillis()));
        int status = ((Number) issued.get(0)).intValue();
        int retryAfter = ((Number) issued.get(1)).intValue();
        if (status == 2) {
            throw new RegistrationCodeLimitException(retryAfter,
                    "该手机号每小时最多发送10条验证码，当前额度已用完，请在" + ((retryAfter + 59) / 60) + "分钟后重试");
        }
        if (status == 0) {
            throw new RegistrationCodeLimitException(retryAfter, "验证码已发送，请在" + retryAfter + "秒后重新获取");
        }
        try {
            delivery.send(phone, code);
        } catch (RuntimeException e) {
            if (e instanceof SmsDeliveryException failure && failure.isRejected()) {
                try {
                    execute(REVOKE_REJECTED, deliveryKeys(key), deliveryId);
                } catch (RuntimeException cleanupFailure) {
                    log.warn("短信拒绝后的缓存清理失败，将由原有 TTL 自动过期");
                }
            }
            log.warn("注册验证码发送失败: {}", e.getClass().getSimpleName());
            // SmsDeliveryException 的提示均由本地代码固定，其他异常仍使用通用提示。
            throw unavailable(e instanceof SmsDeliveryException failure
                    ? failure.getMessage() : "验证码发送失败，请稍后重试");
        }
        return new SendResult(retryAfter, ((Number) issued.get(2)).intValue());
    }

    public void verifyAndConsume(String phone, String code) {
        if (sender.isEmpty()) {
            throw unavailable("注册验证码服务尚未启用");
        }
        if (code == null || !code.matches("[0-9]{6}")
                || !Long.valueOf(1).equals(execute(VERIFY, List.of(codeKey(phone)), code))) {
            throw BusinessException.badRequest("验证码错误或已失效，请核对或重新获取");
        }
    }

    private String codeKey(String phone) {
        return cache.prefixed("auth:registration-code:{" + phone + "}");
    }

    private List<String> deliveryKeys(String key) {
        return List.of(key, key + ":cooldown", key + ":sends");
    }

    private <T> T execute(DefaultRedisScript<T> script, List<String> keys, String... args) {
        try {
            T result = redis.execute(script, keys, (Object[]) args);
            if (result == null) {
                throw unavailable("验证码服务暂不可用，请稍后重试");
            }
            return result;
        } catch (DataAccessException e) {
            log.warn("注册验证码 Redis 操作失败: {}", e.getClass().getSimpleName());
            throw unavailable("验证码服务暂不可用，请稍后重试");
        }
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
