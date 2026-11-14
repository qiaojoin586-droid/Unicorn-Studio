package com.unicorn.studio.common.cache;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 显式指定隔离的 MySQL 和 Redis 才运行；不读取 application.yml 或本地凭据。 */
@EnabledIfSystemProperty(named = "cache.database.integration", matches = "true")
class DatabaseCacheChangeIntegrationTest {
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private String namespace;
    private String settingKey;
    private CacheHelper first;
    private CacheHelper second;
    private DatabaseCacheChangeListener listener;
    private static final String READ_KEY = "cache:site:group:cache-it";

    @BeforeAll
    static void connect() {
        String url = System.getProperty("test.cache.jdbc-url", "");
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cache_coherence_test_[a-z0-9]+(?:\\?.*)?")) {
            throw new IllegalArgumentException("仅允许明确指定本机 cache_coherence_test_* 测试数据库");
        }
        int redisPort = Integer.getInteger("test.cache.redis-port", 0);
        if (redisPort <= 0 || redisPort == 6379) {
            throw new IllegalArgumentException("请指定独立 Redis 测试端口，不能使用默认 6379");
        }
        dataSource = new DriverManagerDataSource(url, "root", "");
        jdbc = new JdbcTemplate(dataSource);
        connections = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", redisPort));
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void disconnect() {
        if (connections != null) connections.destroy();
    }

    @BeforeEach
    void prepare() {
        namespace = "cache-it-" + UUID.randomUUID();
        settingKey = namespace + ".value";
        first = helper();
        second = helper();
        listener = new DatabaseCacheChangeListener(jdbc, first);
        listener.poll();
        new DatabaseCacheChangeListener(jdbc, second).poll();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM site_settings WHERE setting_key = ?", settingKey);
        List<String> owned = new ArrayList<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(namespace + ":*").count(100).build())) {
            cursor.forEachRemaining(owned::add);
        }
        if (!owned.isEmpty()) redis.delete(owned);
    }

    private CacheHelper helper() {
        return new CacheHelper(redis, new CacheCircuitBreaker(3, Duration.ofSeconds(1)), true, 0, namespace, "test");
    }

    private void insert(String value) {
        jdbc.update("INSERT INTO site_settings (group_name, setting_key, setting_value) VALUES ('basic', ?, ?)", settingKey, value);
    }

    private String load(CacheHelper cache) {
        var snapshot = cache.snapshot(READ_KEY);
        return snapshot.get(String.class).orElseGet(() -> {
            List<String> rows = jdbc.query("SELECT setting_value FROM site_settings WHERE setting_key = ?",
                    (rs, row) -> rs.getString(1), settingKey);
            String value = rows.isEmpty() ? "missing" : rows.getFirst();
            snapshot.set(value, Duration.ofMinutes(30));
            return value;
        });
    }

    private Object generation(CacheHelper cache, String domain) {
        return redis.opsForHash().get(cache.prefixed("cache:coherence:" + domain), "generation");
    }

    @Test
    void manualInsertUpdateAndDeleteInvalidateCachesAcrossInstancesWithoutChangingUpdatedAt() {
        assertThat(load(first)).isEqualTo("missing");
        insert("old");
        listener.poll();
        assertThat(load(first)).isEqualTo("old");
        assertThat(load(second)).isEqualTo("old");
        jdbc.update("UPDATE site_settings SET setting_value = 'new' WHERE setting_key = ?", settingKey);
        assertThat(load(first)).isEqualTo("old"); // 轮询前允许短暂陈旧。
        listener.poll();
        assertThat(load(second)).isEqualTo("new"); // 只轮询一个实例，共享代号立即生效。
        jdbc.update("DELETE FROM site_settings WHERE setting_key = ?", settingKey);
        listener.poll();
        assertThat(load(first)).isEqualTo("missing");
    }

    @Test
    void uncommittedAndRolledBackSqlDoesNotPublishANewRevision() throws Exception {
        insert("committed");
        listener.poll();
        assertThat(load(first)).isEqualTo("committed");
        Object before = generation(first, "settings");
        try (Connection transaction = dataSource.getConnection()) {
            transaction.setAutoCommit(false);
            try (var update = transaction.prepareStatement("UPDATE site_settings SET setting_value = 'uncommitted' WHERE setting_key = ?")) {
                update.setString(1, settingKey);
                update.executeUpdate();
            }
            listener.poll();
            assertThat(generation(first, "settings")).isEqualTo(before);
            assertThat(load(second)).isEqualTo("committed");
            transaction.rollback();
        }
        listener.poll();
        assertThat(generation(first, "settings")).isEqualTo(before);
        assertThat(load(first)).isEqualTo("committed");
    }

    @Test
    void anOldQueryCannotWriteIntoTheNewGenerationAfterManualSql() {
        insert("old");
        listener.poll();
        var slowQuery = first.snapshot(READ_KEY);
        jdbc.update("UPDATE site_settings SET setting_value = 'new' WHERE setting_key = ?", settingKey);
        listener.poll();
        slowQuery.set("old", Duration.ofMinutes(30));
        assertThat(load(second)).isEqualTo("new");
    }

    @Test
    void redisFailureRetriesTheUnappliedDatabaseRevision() {
        insert("old");
        listener.poll();
        assertThat(load(first)).isEqualTo("old");
        jdbc.update("UPDATE site_settings SET setting_value = 'new' WHERE setting_key = ?", settingKey);
        CacheHelper interrupted = spy(first);
        doThrow(new IllegalStateException("simulated Redis disconnect")).when(interrupted)
                .applyDatabaseRevision(anyString(), anyString(), anyString());
        var retrying = new DatabaseCacheChangeListener(jdbc, interrupted);
        retrying.poll();
        assertThat(interrupted.snapshot(READ_KEY).get(String.class)).isEmpty();
        doCallRealMethod().when(interrupted).applyDatabaseRevision(anyString(), anyString(), anyString());
        retrying.poll();
        assertThat(load(second)).isEqualTo("new");
    }

    @Test
    void restartingAListenerCatchesChangesMadeWhileTheApplicationWasDown() {
        insert("old");
        listener.poll();
        assertThat(load(first)).isEqualTo("old");
        jdbc.update("UPDATE site_settings SET setting_value = 'new' WHERE setting_key = ?", settingKey);
        CacheHelper restarted = helper();
        assertThat(restarted.snapshot(READ_KEY).get(String.class)).isEmpty();
        new DatabaseCacheChangeListener(jdbc, restarted).poll();
        assertThat(load(restarted)).isEqualTo("new");
    }

    @Test
    void missingTriggersDisableCachingAndRecoveryResumesAutomatically() {
        String ddl = jdbc.queryForMap("SHOW CREATE TRIGGER us_cache_site_settings_update").get("SQL Original Statement").toString();
        insert("old");
        listener.poll();
        assertThat(load(first)).isEqualTo("old");
        try {
            jdbc.execute("DROP TRIGGER us_cache_site_settings_update");
            ReflectionTestUtils.setField(listener, "lastSchemaCheckNanos", 0L);
            listener.poll();
            assertThat(first.snapshot(READ_KEY).get(String.class)).isEmpty();
            // 即使触发器缺失期间有写入、版本没有推进，恢复时也不能重新启用旧值。
            jdbc.update("UPDATE site_settings SET setting_value = 'during-gap' WHERE setting_key = ?", settingKey);
        } finally {
            jdbc.execute(ddl);
        }
        listener.poll();
        assertThat(load(first)).isEqualTo("during-gap");
    }

    @Test
    void luaRejectsOutOfOrderRevisionsIncludingBigintsBeyondDoublePrecision() {
        first.applyDatabaseRevision("works", "test-epoch", "9007199254740993");
        Object newest = generation(first, "works");
        first.applyDatabaseRevision("works", "test-epoch", "9007199254740992");
        assertThat(generation(first, "works")).isEqualTo(newest);
        first.applyDatabaseRevision("works", "test-epoch", "9007199254740994");
        assertThat(generation(first, "works")).isNotEqualTo(newest);
    }
}
