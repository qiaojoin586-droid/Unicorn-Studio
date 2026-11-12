package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式启用时运行真实 Redis Lua；仅使用随机隔离的测试键，短信发送器始终为 mock。 */
@EnabledIfSystemProperty(named = "registration.redis.integration", matches = "true")
class RegistrationCodeQuotaRedisTest {
    private static final String PHONE = "13900000000";
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private final String prefix = "test:sms-quota:" + UUID.randomUUID() + ":";
    private final Set<String> baseKeys = ConcurrentHashMap.newKeySet();
    private final CacheHelper cache = mock(CacheHelper.class);
    private final RegistrationSmsSender sender = mock(RegistrationSmsSender.class);
    private RegistrationCodeService service;

    @BeforeAll
    static void connect() {
        var server = new RedisStandaloneConfiguration(System.getProperty("test.redis.host", "127.0.0.1"),
                Integer.getInteger("test.redis.port", 6379));
        connections = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections) {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                try { return super.execute(script, keys, args); }
                catch (org.springframework.dao.DataAccessException e) {
                    // 仅限独立测试 Redis 和随机测试键，便于定位 Lua 在不同版本 Redis 上的兼容性。
                    throw new AssertionError("Isolated Redis test: " + e.getMostSpecificCause().getMessage());
                }
            }
        };
    }

    @AfterAll
    static void disconnect() { connections.destroy(); }

    @BeforeEach
    void prepare() {
        when(cache.prefixed(anyString())).thenAnswer(invocation -> {
            String key = prefix + invocation.<String>getArgument(0);
            baseKeys.add(key);
            return key;
        });
        service = new RegistrationCodeService(redis, cache, Optional.of(sender));
    }

    @AfterEach
    void cleanupOnlyOwnedKeys() {
        var owned = new ArrayList<String>();
        for (String key : baseKeys) {
            assertThat(key).startsWith(prefix);
            owned.addAll(List.of(key, key + ":cooldown", key + ":sends"));
        }
        if (!owned.isEmpty()) redis.delete(owned);
    }

    private String key(String phone) { return cache.prefixed("auth:registration-code:{" + phone + "}"); }

    private void seed(String phone, int count) {
        String quota = key(phone) + ":sends";
        for (int i = 0; i < count; i++) {
            redis.opsForZSet().add(quota, "prior-" + i, System.currentTimeMillis() - (10L + i) * 60_000);
        }
        redis.expire(quota, Duration.ofHours(1));
    }

    @Test
    void allowsResendAfterCooldownWithoutChargingRepeatedClicks() throws Exception {
        var first = service.send(PHONE);
        assertThat(first.remainingHourlySends()).isEqualTo(9);
        String code = String.valueOf(redis.opsForHash().get(key(PHONE), "code"));
        for (int i = 0; i < 12; i++) {
            assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(RegistrationCodeLimitException.class);
        }
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(1);
        assertThat(redis.opsForHash().get(key(PHONE), "code")).isEqualTo(code);
        // 只缩短当前测试号码的冷却 TTL，模拟60秒已过，不等待真实一分钟。
        redis.expire(key(PHONE) + ":cooldown", Duration.ofMillis(1));
        Thread.sleep(20);
        assertThat(service.send(PHONE).remainingHourlySends()).isEqualTo(8);
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(2);
        verify(sender, times(2)).send(eq(PHONE), anyString());
        assertThat(redis.getExpire(key(PHONE))).isBetween(295L, 300L);
    }

    @Test
    void sendsTenthButRejectsEleventhWithExplicitHourlyMessage() {
        seed(PHONE, 9);
        var tenth = service.send(PHONE);
        assertThat(tenth.remainingHourlySends()).isZero();
        assertThat(tenth.retryAfterSeconds()).isBetween(2400, 2600);
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOfSatisfying(RegistrationCodeLimitException.class,
                error -> {
                    assertThat(error.getReason()).contains("每小时最多发送10条", "当前额度已用完");
                    assertThat(error.getRetryAfterSeconds()).isBetween(2400, 2600);
                });
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(10);
        verify(sender, times(1)).send(eq(PHONE), anyString());
    }

    @Test
    void restoresCapacityWhenOldestSendLeavesRollingHour() {
        seed(PHONE, 9);
        redis.opsForZSet().add(key(PHONE) + ":sends", "expired", System.currentTimeMillis() - 3_601_000);
        assertThat(service.send(PHONE).remainingHourlySends()).isZero();
        assertThat(redis.opsForZSet().score(key(PHONE) + ":sends", "expired")).isNull();
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(10);
    }

    @Test
    void definiteProviderRejectionRefundsQuotaAndAllowsImmediateRetry() {
        doThrow(SmsDeliveryException.permissionDenied()).doNothing().when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(key(PHONE) + ":sends")).isFalse();
        assertThat(redis.hasKey(key(PHONE) + ":cooldown")).isFalse();
        assertThat(redis.hasKey(key(PHONE))).isFalse();
        assertThat(service.send(PHONE).remainingHourlySends()).isEqualTo(9);
    }

    @Test
    void unknownNetworkResultRetainsReservationWithoutChargingBlockedRetry() {
        doThrow(new SmsDeliveryException(false)).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(RegistrationCodeLimitException.class);
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(1);
        assertThat(redis.hasKey(key(PHONE))).isTrue();
        verify(sender, times(1)).send(eq(PHONE), anyString());
    }

    @Test
    void differentPhonesHaveIndependentQuota() {
        seed(PHONE, 10);
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(RegistrationCodeLimitException.class);
        assertThat(service.send("13800000000").remainingHourlySends()).isEqualTo(9);
        verify(sender, never()).send(eq(PHONE), anyString());
        verify(sender).send(eq("13800000000"), anyString());
    }

    @Test
    void concurrentRequestsReserveOnlyOneDelivery() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var requests = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                requests.add(pool.submit(() -> {
                    start.await();
                    try { service.send(PHONE); return true; }
                    catch (RegistrationCodeLimitException e) { return false; }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var request : requests) if (request.get(10, TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
        }
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(1);
        verify(sender, times(1)).send(eq(PHONE), anyString());
    }

    @Test
    void lateRejectionRefundsOnlyItsOwnReservation() {
        doAnswer(invocation -> {
            String base = key(PHONE);
            redis.opsForHash().put(base, "delivery", "newer-delivery");
            redis.opsForHash().put(base, "code", "654321");
            redis.opsForValue().set(base + ":cooldown", "newer-delivery", Duration.ofSeconds(60));
            redis.opsForZSet().add(base + ":sends", "newer-delivery", System.currentTimeMillis());
            throw new SmsDeliveryException(true);
        }).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().get(key(PHONE), "code")).isEqualTo("654321");
        assertThat(redis.opsForValue().get(key(PHONE) + ":cooldown")).isEqualTo("newer-delivery");
        assertThat(redis.opsForZSet().range(key(PHONE) + ":sends", 0, -1)).containsExactly("newer-delivery");
    }

    @Test
    void consumingCodeDoesNotResetHourlyQuota() {
        seed(PHONE, 9);
        service.send(PHONE);
        service.verifyAndConsume(PHONE, String.valueOf(redis.opsForHash().get(key(PHONE), "code")));
        assertThat(redis.hasKey(key(PHONE))).isFalse();
        assertThatThrownBy(() -> service.send(PHONE)).isInstanceOfSatisfying(RegistrationCodeLimitException.class,
                error -> assertThat(error.getReason()).contains("每小时最多发送10条"));
        assertThat(redis.opsForZSet().zCard(key(PHONE) + ":sends")).isEqualTo(10);
    }
}
