package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.vo.JoinPageVO;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JoinPageServiceTest {
    @Test
    void savedQrOverridesPrimedPageCacheAndCanBeClearedWithoutLosingPageContent() {
        var settings = mock(SiteSettingsService.class);
        var cache = mock(CacheHelper.class);
        var snapshot = mock(CacheHelper.Snapshot.class);
        when(cache.snapshot(CacheKeys.JOIN_PAGE)).thenReturn(snapshot);
        Map<String, String> database = new HashMap<>();
        Map<String, JoinPageVO> pages = new HashMap<>();
        when(settings.listGroup("join")).thenAnswer(invocation -> Map.copyOf(database));
        when(snapshot.get(JoinPageVO.class)).thenAnswer(invocation -> Optional.ofNullable(pages.get(CacheKeys.JOIN_PAGE)));
        doAnswer(invocation -> {
            pages.put(CacheKeys.JOIN_PAGE, invocation.getArgument(0)); return null;
        }).when(snapshot).set(any(), any(Duration.class));
        doAnswer(invocation -> {
            List<SiteSettingsService.KeyValueRequest> values = invocation.getArgument(0);
            values.forEach(value -> database.put(value.key(), value.value())); return null;
        }).when(settings).updateAll(anyList());
        doAnswer(invocation -> { pages.remove(CacheKeys.JOIN_PAGE); return null; })
                .when(cache).evictAfterCommit(any(String[].class));
        var service = new JoinPageService(settings, cache);

        assertThat(service.load().qrImage()).isEmpty();
        assertThat(pages).containsKey(CacheKeys.JOIN_PAGE);
        var custom = new JoinPageVO("JOIN", "一起创造", "社群邀请", List.of("原有正文"), "/uploads/new-community.jpg", "新社群二维码");
        service.save(custom);
        assertThat(database.get("join.qr_image")).isEqualTo("/uploads/new-community.jpg");
        assertThat(pages).isEmpty();
        assertThat(service.load()).isEqualTo(custom);

        service.save(new JoinPageVO(custom.eyebrow(), custom.title(), custom.subtitle(), custom.paragraphs(), "", custom.qrCaption()));
        assertThat(service.load().qrImage()).isEmpty();
        assertThat(service.load().title()).isEqualTo(custom.title());
        assertThat(service.load().paragraphs()).isEqualTo(custom.paragraphs());
        verify(cache, times(2)).evictAfterCommit(CacheKeys.JOIN_PAGE);
    }
}
