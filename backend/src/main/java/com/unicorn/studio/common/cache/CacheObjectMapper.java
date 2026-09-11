package com.unicorn.studio.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 缓存专用 ObjectMapper 工厂（Redis 方案 §5.5.1 / §5.5.2 / R6）。
 * <p>
 * 独立构建、不注入 Spring 的 MVC ObjectMapper，原因：
 * <ol>
 *   <li>定义 {@code ObjectMapper} Bean 会触发 {@code @ConditionalOnMissingBean}，覆盖 Spring Boot
 *       自动配置的 MVC ObjectMapper，从而影响所有 REST 接口的 JSON 序列化口径；</li>
 *   <li>复用 MVC ObjectMapper 则可能被后续 {@code spring.jackson.*} / 自定义 builder 悄然改变，
 *       且实测 {@code Jackson2ObjectMapperBuilder} 会把 {@code LocalDateTime} 序列化成时间戳数组
 *       （{@code WRITE_DATES_AS_TIMESTAMPS} 未关闭），违反 §5.5.2 的无时区 ISO-8601 口径。</li>
 * </ol>
 * <p>
 * 口径：注册 {@link JavaTimeModule}、关闭 {@code WRITE_DATES_AS_TIMESTAMPS}，
 * 输出/读入无时区的 {@code 2026-09-12T11:23:59}，与全链路无时区 + 固定 Asia/Shanghai 的约定一致。
 */
public final class CacheObjectMapper {

    private CacheObjectMapper() {
    }

    public static ObjectMapper create() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
