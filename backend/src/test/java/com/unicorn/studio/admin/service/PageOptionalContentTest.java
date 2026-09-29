package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageOptionalContentTest {
    private SiteSettingsService settings;
    private CacheHelper cache;
    private Map<String,Map<String,String>> groups;
    @BeforeEach void setup() {
        settings = mock(SiteSettingsService.class);
        cache = mock(CacheHelper.class);
        groups = new HashMap<>();
        when(settings.listGroup(anyString())).thenAnswer(call -> groups.getOrDefault(call.getArgument(0), Map.of()));
        when(cache.get(CacheKeys.ABOUT_PAGE, AboutPageVO.class)).thenReturn(Optional.empty());
        when(cache.get(CacheKeys.JOIN_PAGE, JoinPageVO.class)).thenReturn(Optional.empty());
        when(cache.get(CacheKeys.FOOTER_PAGE, FooterVO.class)).thenReturn(Optional.empty());
    }
    @Test void missingAboutHasTruthfulTextAndNoInventedStatistics() {
        var about = new AboutPageService(settings, cache).load();
        assertTrue(about.stats().isEmpty());
        assertTrue(about.content().contains("学习与实践"));
        assertTrue(about.content().contains("Agent"));
        assertFalse(about.content().contains("众多企业"));
    }
    @Test void aboutOptionalEmptyFieldsStayEmptyButRequiredTitleFallsBack() {
        groups.put("about", Map.of("about.title","", "about.eyebrow","", "about.subtitle","", "about.content","", "about.image_url","", "about.stats","[]"));
        var about = new AboutPageService(settings, cache).load();
        assertFalse(about.title().isBlank());
        assertEquals("", about.eyebrow());
        assertEquals("", about.subtitle());
        assertEquals("", about.content());
        assertEquals("", about.imageUrl());
        assertTrue(about.stats().isEmpty());
    }
    @Test void realStoredAboutStatisticsArePreserved() {
        groups.put("about", Map.of("about.stats","[{\"value\":\"12\",\"label\":\"已核实项目\"}]"));
        assertEquals(List.of(new AboutPageVO.Stat("12","已核实项目")), new AboutPageService(settings, cache).load().stats());
    }
    @Test void joinExplicitEmptyParagraphsAndOptionalStringsStayEmpty() {
        groups.put("join", Map.of("join.title","", "join.eyebrow","", "join.subtitle","", "join.paragraphs","", "join.qr_image","", "join.qr_caption",""));
        var join = new JoinPageService(settings, cache).load();
        assertFalse(join.title().isBlank());
        assertEquals("", join.eyebrow());
        assertEquals("", join.subtitle());
        assertEquals("", join.qrImage());
        assertEquals("", join.qrCaption());
        assertTrue(join.paragraphs().isEmpty());
    }
    @Test void missingJoinParagraphsStillReceiveDefaults() {
        assertFalse(new JoinPageService(settings, cache).load().paragraphs().isEmpty());
    }
    @Test void footerOptionalStringsNeverReturnAfterBeingExplicitlyCleared() {
        var values = new HashMap<String,String>();
        for (String name : List.of("brand_name","brand_intro","links_title","social_title","email","email_label","copyright_left","copyright_right")) values.put("footer." + name, "");
        values.put("footer.links", "[]"); values.put("footer.socials", "[]");
        groups.put("footer", values);
        var footer = new FooterService(settings, cache).load();
        assertFalse(footer.brandName().isBlank());
        assertEquals("", footer.brandIntro());
        assertEquals("", footer.linksTitle());
        assertEquals("", footer.socialTitle());
        assertEquals("", footer.email());
        assertEquals("", footer.emailLabel());
        assertEquals("", footer.copyrightLeft());
        assertEquals("", footer.copyrightRight());
        assertTrue(footer.links().isEmpty()); assertTrue(footer.socials().isEmpty());
    }
}