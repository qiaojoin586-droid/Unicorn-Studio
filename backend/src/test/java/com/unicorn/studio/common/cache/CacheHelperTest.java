package com.unicorn.studio.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.vo.WorkDetailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CacheHelper 降级行为单测（Redis 方案 §6.1 / 附录 C.2）。
 * 核心断言：Redis 任何异常都不向外抛，读返回 empty、写/删静默；enabled=false 时彻底跳过 Redis。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheHelperTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper mapper;
    private CacheCircuitBreaker breaker;
    private CacheHelper helper;

    @BeforeEach
    void setUp() {
        mapper = CacheObjectMapper.create();
        when(redis.opsForValue()).thenReturn(valueOps);
        breaker = new CacheCircuitBreaker(3, Duration.ofSeconds(30));
        helper = new CacheHelper(redis, breaker, true, 0.0);
    }

    @Test
    void get_whenMiss_returnsEmpty() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertTrue(helper.get("cache:work:detail:a", WorkDetailVO.class).isEmpty());
    }

    @Test
    void get_whenHit_deserializes() {
        WorkDetailVO vo = new WorkDetailVO(1L, "slug-a", "标题", "论文", "cover.jpg", "摘要",
                "正文", null, "\uD83C\uDFC6", "bg", "paper", "张三", "2026", 5,
                java.time.LocalDateTime.of(2026, 9, 12, 11, 23, 59));
        String json = toJson(vo);
        when(valueOps.get("cache:work:detail:slug-a")).thenReturn(json);

        Optional<WorkDetailVO> result = helper.get("cache:work:detail:slug-a", WorkDetailVO.class);
        assertTrue(result.isPresent());
        assertEquals(vo, result.get());
    }

    @Test
    void get_whenRedisThrows_returnsEmptyAndDoesNotPropagate() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertTrue(helper.get("cache:work:detail:a", WorkDetailVO.class).isEmpty());
    }

    @Test
    void set_whenRedisThrows_doesNotPropagate() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        helper.set("k", "v", Duration.ofMinutes(30)); // 不应抛异常
    }

    @Test
    void disabled_skipsRedisEntirely() {
        CacheHelper disabled = new CacheHelper(redis, breaker, false, 0.0);
        disabled.get("k", WorkDetailVO.class);
        disabled.set("k", "v", Duration.ofMinutes(1));
        disabled.delete("k");
        verify(redis, never()).opsForValue();
    }

    @Test
    void breaker_opensAfterThresholdFailuresAndSkipsRedis() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        // 连续 3 次失败 → 打开熔断
        helper.get("k1", WorkDetailVO.class);
        helper.get("k2", WorkDetailVO.class);
        helper.get("k3", WorkDetailVO.class);
        assertTrue(breaker.isOpen(), "连续失败达到阈值后应打开熔断");

        // 熔断打开后，第 4 次 get 直接返回 empty，不再触碰 Redis
        helper.get("k4", WorkDetailVO.class);
        verify(valueOps, times(3)).get(anyString());
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
