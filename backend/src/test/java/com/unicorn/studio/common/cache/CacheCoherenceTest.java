package com.unicorn.studio.common.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheCoherenceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> data = new HashMap<>();
    private final Map<String, String> generations = new HashMap<>();
    private CacheHelper cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(hashes.get(anyString(), eq("generation"))).thenAnswer(call -> generations.get(call.getArgument(0)));
        doAnswer(call -> { generations.put(call.getArgument(0), call.getArgument(2)); return null; })
                .when(hashes).put(anyString(), eq("generation"), anyString());
        when(values.get(anyString())).thenAnswer(call -> data.get(call.getArgument(0)));
        doAnswer(call -> { data.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        cache = new CacheHelper(redis, new CacheCircuitBreaker(10, Duration.ofSeconds(1)), true, 0, "test", "local");
        generations.put(cache.prefixed("cache:coherence:settings"), "initial");
        generations.put(cache.prefixed("cache:coherence:works"), "initial");
        cache.databaseChangesAvailable();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void aSlowReaderCannotRepopulateTheCurrentGenerationAfterInvalidation() {
        var slow = cache.snapshot(CacheKeys.ABOUT_PAGE);
        assertThat(slow.get(String.class)).isEmpty();
        cache.evictAfterCommit(CacheKeys.ABOUT_PAGE);
        slow.set("old database value", Duration.ofMinutes(30));
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
        cache.snapshot(CacheKeys.ABOUT_PAGE).set("new value", Duration.ofMinutes(30));
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).contains("new value");
        assertThat(data).hasSize(2); // 旧代号保留原 TTL，但后续请求无法再命中。
    }

    @Test
    void settingsInvalidationAlsoInvalidatesNestedGroupAndPageCaches() {
        cache.snapshot("cache:site:group:about").set(Map.of("title", "old"), Duration.ofMinutes(30));
        var oldPage = cache.snapshot(CacheKeys.ABOUT_PAGE);
        cache.evictAfterCommit("cache:site:settings");
        oldPage.set("assembled from old group", Duration.ofMinutes(30));
        assertThat(cache.snapshot("cache:site:group:about").get(Map.class)).isEmpty();
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
    }

    @Test
    void negativeCachesUseTheSameGenerationAsTheirContent() {
        var absent = cache.snapshot(CacheKeys.nullWork("new-slug"));
        cache.evictAfterCommit(CacheKeys.workDetail("new-slug"));
        absent.setString("__NULL__", Duration.ofSeconds(60));
        assertThat(cache.snapshot(CacheKeys.nullWork("new-slug")).getString()).isEmpty();
    }

    @Test
    void rollbackDoesNotInvalidateAndActiveTransactionsCannotPublishUncommittedValues() {
        cache.snapshot(CacheKeys.ABOUT_PAGE).set("committed", Duration.ofMinutes(30));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var uncommitted = cache.snapshot(CacheKeys.ABOUT_PAGE);
        assertThat(uncommitted.get(String.class)).isEmpty();
        uncommitted.set("uncommitted", Duration.ofMinutes(30));
        cache.evictAfterCommit(CacheKeys.ABOUT_PAGE);
        for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clear();
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).contains("committed");
    }

    @Test
    void onlyCommittedApplicationChangesRotateTheGeneration() {
        cache.snapshot(CacheKeys.ABOUT_PAGE).set("old", Duration.ofMinutes(30));
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit(CacheKeys.ABOUT_PAGE);
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).contains("old");
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.clear();
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
    }

    @Test
    void missingMigrationLostRedisMetadataAndAnExpiredListenerLeaseBypassReadCaches() {
        cache.snapshot(CacheKeys.ABOUT_PAGE).set("old", Duration.ofMinutes(30));
        cache.databaseChangesUnavailable();
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
        cache.databaseChangesAvailable();
        ReflectionTestUtils.setField(cache, "lastDatabaseSyncNanos", System.nanoTime() - 10_000_000_000L);
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
        cache.databaseChangesAvailable();
        generations.clear();
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).isEmpty();
    }

    @Test
    void environmentPrefixesAndOperationalRedisKeysStaySeparate() {
        cache.snapshot(CacheKeys.ABOUT_PAGE).set("page", Duration.ofMinutes(30));
        cache.delete("rate:login:ip", "cache:lock:warmup");
        assertThat(cache.snapshot(CacheKeys.ABOUT_PAGE).get(String.class)).contains("page");
        assertThat(data.keySet()).allMatch(key -> key.startsWith("test:local:"));
    }
}
