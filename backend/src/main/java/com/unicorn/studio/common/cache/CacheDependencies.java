package com.unicorn.studio.common.cache;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** SQL 生成器和运行时共用的数据库表 → 缓存域依赖表。 */
final class CacheDependencies {
    record Definition(Map<String, List<String>> domains, Map<String, List<String>> tables) {}

    static final Definition DEFINITION = load();

    private CacheDependencies() {}

    static String domain(String key) {
        return DEFINITION.domains().entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(key::startsWith))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    static String triggerName(String table, String event) {
        return "us_cache_" + table + "_" + event.toLowerCase(java.util.Locale.ROOT);
    }

    private static Definition load() {
        try (InputStream input = CacheDependencies.class.getResourceAsStream("/cache-dependencies.json")) {
            if (input == null) {
                throw new IllegalStateException("缺少 cache-dependencies.json");
            }
            return CacheObjectMapper.create().readValue(input, Definition.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取缓存依赖定义", e);
        }
    }
}
