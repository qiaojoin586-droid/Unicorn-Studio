package com.unicorn.studio.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationCodeDeliveryTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RegistrationSmsSender sender = mock(RegistrationSmsSender.class);
    private final CacheHelper cache = mock(CacheHelper.class);
    private final RegistrationCodeService service = new RegistrationCodeService(redis, cache, Optional.of(sender));
    private final List<Object[]> calls = new ArrayList<>();

    RegistrationCodeDeliveryTest() {
        when(cache.prefixed(anyString())).thenAnswer(invocation -> "unit:" + invocation.getArgument(0));
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    calls.add(((Object[]) invocation.getRawArguments()[2]).clone());
                    RedisScript<?> script = invocation.getArgument(0);
                    return script.getResultType() == List.class ? List.of(1L, 60L, 9L) : 1L;
                });
    }

    @Test
    void storesFiveMinuteCodeBeforeSendingTheSameCode() {
        doAnswer(invocation -> {
            assertThat(calls).hasSize(1);assertThat(invocation.<String>getArgument(1)).isEqualTo(calls.getFirst()[1]);return null;
        }).when(sender).send(anyString(), anyString());
        var result = service.send("13900000000");
        assertThat(calls.getFirst()[0]).isEqualTo("60");assertThat((String) calls.getFirst()[1]).matches("[0-9]{6}");
        assertThat(calls.getFirst()[2]).isEqualTo("5");assertThat(calls.getFirst()[3]).isEqualTo("300");
        assertThat(calls.getFirst()[5]).isEqualTo("3600");assertThat(calls.getFirst()[6]).isEqualTo("10");
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
        assertThat(result.remainingHourlySends()).isEqualTo(9);
        verify(sender).send(eq("13900000000"), anyString());
    }

    @Test
    void definiteRejectionRevokesOnlyOriginalDeliveryReservation() {
        doThrow(new SmsDeliveryException(true)).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOf(BusinessException.class);
        assertThat(calls).hasSize(2);assertThat(calls.get(1)).containsExactly(calls.getFirst()[4]);
    }

    @Test
    void permissionFailureReturnsActionableMessageAndRevokesReservation() {
        doThrow(SmsDeliveryException.permissionDenied()).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOfSatisfying(BusinessException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).isEqualTo("短信服务未获得发送权限，请联系管理员");
                });
        assertThat(calls).hasSize(2);
        assertThat(calls.get(1)).containsExactly(calls.getFirst()[4]);
    }

    @Test
    void unexpectedFailureDoesNotExposeRawExceptionMessage() {
        doThrow(new IllegalStateException("unit-secret 012345 13900000000")).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getReason()).isEqualTo("验证码发送失败，请稍后重试"));
        assertThat(calls).hasSize(1);
    }

    @Test
    void unknownNetworkFailureKeepsCodeAndCooldown() {
        doThrow(new SmsDeliveryException(false)).when(sender).send(anyString(), anyString());
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOf(BusinessException.class);assertThat(calls).hasSize(1);
    }

    @Test
    void redisFailureDoesNotSendSms() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Object>>any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("unit unavailable"));
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOf(BusinessException.class);
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void cooldownPreventsSending() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<List>>any(), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 42L, 9L));
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOfSatisfying(BusinessException.class,
                error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getReason()).contains("42秒后");
                });
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void hourlyQuotaExplainsTenMessageLimitAndRemainingWait() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<List>>any(), anyList(), any(Object[].class)))
                .thenReturn(List.of(2L, 1801L, 0L));
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOfSatisfying(RegistrationCodeLimitException.class,
                error -> {
                    assertThat(error.getRetryAfterSeconds()).isEqualTo(1801);
                    assertThat(error.getReason()).contains("每小时最多发送10条", "31分钟后");
                });
        verify(sender, never()).send(anyString(), anyString());
    }

    @Test
    void configurationFailureDoesNotStoreAnyCode() {
        doThrow(new SmsDeliveryException(true)).when(sender).checkAvailable();
        assertThatThrownBy(() -> service.send("13900000000")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(redis);
    }
}
