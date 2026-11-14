package com.unicorn.studio.common.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 读取与业务事务一同提交的版本行；版本保存在数据库，进程停机和 Redis 故障不会丢通知。 */
@Component
@ConditionalOnProperty(name = "app.cache.database-changes.enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseCacheChangeListener {
    private static final Logger log = LoggerFactory.getLogger(DatabaseCacheChangeListener.class);
    private final JdbcTemplate jdbc;
    private final CacheHelper cache;
    private long lastSchemaCheckNanos;
    private boolean healthy;
    private boolean failureLogged;

    public DatabaseCacheChangeListener(JdbcTemplate jdbc, CacheHelper cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    record Revision(String domain, String epoch, String revision) {}

    @Scheduled(fixedDelayString = "${app.cache.database-changes.poll-interval-ms:1000}")
    public synchronized void poll() {
        if (!cache.isEnabled()) return;
        try {
            long started = System.nanoTime();
            if (lastSchemaCheckNanos == 0 || started - lastSchemaCheckNanos > 60_000_000_000L) {
                verifyTriggers();
                lastSchemaCheckNanos = started;
            }
            List<Revision> revisions = jdbc.query(
                    "SELECT cache_domain, epoch, CAST(revision AS CHAR) AS revision FROM cache_revision",
                    (rs, row) -> new Revision(rs.getString("cache_domain"), rs.getString("epoch"), rs.getString("revision")));
            Set<String> domains = new HashSet<>();
            for (Revision revision : revisions) {
                if (revision.epoch() == null || revision.epoch().isBlank()
                        || revision.revision() == null || !revision.revision().matches("[1-9][0-9]*")
                        || !domains.add(revision.domain())) {
                    throw new IllegalStateException("数据库缓存版本行无效");
                }
            }
            if (!domains.equals(CacheDependencies.DEFINITION.domains().keySet())) {
                throw new IllegalStateException("数据库缓存依赖域不完整，请执行 02-upgrade.sql");
            }
            for (Revision revision : revisions) {
                cache.applyDatabaseRevision(revision.domain(), revision.epoch(), revision.revision());
            }
            if (!healthy) cache.resetDatabaseGenerations();
            cache.databaseChangesAvailable();
            if (!healthy) log.info("数据库缓存变更监听已就绪（{} 个缓存域）", domains.size());
            healthy = true;
        } catch (Exception e) {
            cache.databaseChangesUnavailable();
            lastSchemaCheckNanos = 0;
            if (healthy || !failureLogged) {
                log.warn("数据库缓存变更监听不可用，读缓存暂时停用并回源 MySQL；将自动重试。请确认已执行 02-upgrade.sql: {}", e.getMessage());
            }
            failureLogged = true;
            healthy = false;
        }
    }

    private void verifyTriggers() {
        Map<String, String> expected = new java.util.HashMap<>();
        CacheDependencies.DEFINITION.tables().keySet().forEach(table -> {
            for (String event : List.of("INSERT", "UPDATE", "DELETE")) {
                expected.put(CacheDependencies.triggerName(table, event), table + ":" + event);
            }
        });
        Set<String> found = new HashSet<>();
        jdbc.query("SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, EVENT_MANIPULATION, ACTION_TIMING "
                        + "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE()",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String name = rs.getString("TRIGGER_NAME");
                    String target = rs.getString("EVENT_OBJECT_TABLE") + ":" + rs.getString("EVENT_MANIPULATION");
                    if (target.equals(expected.get(name)) && "AFTER".equals(rs.getString("ACTION_TIMING"))) {
                        found.add(name);
                    }
                });
        if (!found.equals(expected.keySet())) {
            throw new IllegalStateException("数据库缓存触发器不完整，请执行 02-upgrade.sql");
        }
    }
}
