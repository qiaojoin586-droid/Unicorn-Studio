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

@Service
public class RegistrationCodeService {
    public static final int RESEND_SECONDS = 60;
    private static final int VALID_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(RegistrationCodeService.class);

    // 发码间隔、验证码和有效期一起写入，避免并发请求生成两条有效验证码。
    private static final DefaultRedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            if not redis.call('SET', KEYS[2], '1', 'NX', 'EX', ARGV[1]) then return 0 end
            redis.call('HMSET', KEYS[1], 'code', ARGV[2], 'attempts', ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

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

    private final StringRedisTemplate redis;
    private final CacheHelper cache;
    private final Optional<RegistrationSmsSender> sender;

    public RegistrationCodeService(StringRedisTemplate redis, CacheHelper cache,
                                   Optional<RegistrationSmsSender> sender) {
        this.redis = redis;
        this.cache = cache;
        this.sender = sender;
    }

    public void send(String phone) {
        RegistrationSmsSender delivery = sender.orElseThrow(() -> unavailable("注册验证码服务尚未启用"));
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String key = codeKey(phone);
        Long issued = execute(ISSUE, List.of(key, key + ":cooldown"), Integer.toString(RESEND_SECONDS), code,
                Integer.toString(MAX_ATTEMPTS), Integer.toString(VALID_SECONDS));
        if (!Long.valueOf(1).equals(issued)) {
            throw BusinessException.tooManyRequests("验证码请求过于频繁，请稍后再试");
        }
        try {
            delivery.send(phone, code);
        } catch (RuntimeException e) {
            log.warn("注册验证码发送失败: {}", e.getClass().getSimpleName());
            throw unavailable("验证码发送失败，请稍后重试");
        }
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

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long result = redis.execute(script, keys, (Object[]) args);
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
