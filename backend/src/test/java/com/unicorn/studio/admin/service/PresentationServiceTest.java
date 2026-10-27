package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.service.SensitiveWordService;
import com.unicorn.studio.vo.PresentationVO;
import jakarta.validation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresentationServiceTest {
    private static ValidatorFactory validationFactory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private SiteSettingsService settings;
    private PresentationService service;
    private Map<String,String> stored;
    @BeforeAll static void validator() { validationFactory = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void closeValidator() { validationFactory.close(); }
    @BeforeEach void setup() {
        settings = mock(SiteSettingsService.class);
        stored = new HashMap<>();
        when(settings.listGroup("content")).thenAnswer(ignored -> new HashMap<>(stored));
        doAnswer(invocation -> {
            List<SiteSettingsService.KeyValueRequest> items = invocation.getArgument(0);
            items.forEach(item -> stored.put(item.key(), item.value()));
            return null;
        }).when(settings).updateAll(anyList());
        service = new PresentationService(settings, validationFactory.getValidator());
    }
    @Test void generatedDefaultsMatchPublicContract() throws Exception {
        JsonNode expected;
        try (var input = getClass().getResourceAsStream("/presentation-defaults.json")) { expected = JSON.readTree(input); }
        assertEquals(expected, JSON.valueToTree(service.load()));
        verify(settings).listGroup("content");
    }
    @Test void partialStoredFieldsMergeDefaultsWhileEmptyAndUnknownFieldsRemainSafe() throws Exception {
        stored.put("content.presentation.home", "{\"title\":\"真实标题\",\"description\":\"\",\"adminSecret\":\"hidden\"}");
        var home = service.loadHome();
        assertEquals("真实标题", home.title());
        assertEquals("", home.description());
        assertEquals("探索作品", home.primaryLabel());
        assertFalse(JSON.valueToTree(home).has("adminSecret"));
    }
    @Test void explicitEmptyPrinciplesAreNotReplacedByDefaults() {
        stored.put("content.presentation.about", "{\"principles\":[]}");
        assertTrue(service.loadAbout().principles().isEmpty());
    }
    @Test void partialSavePreservesPriorOptionalFieldsAndUsesOnlyDedicatedKey() throws Exception {
        stored.put("content.presentation.home", "{\"primaryLabel\":\"已保存入口\"}");
        var result = service.saveHome(JSON.readValue("{\"title\":\"新的标题\",\"description\":\"\"}", PresentationVO.Home.class));
        assertEquals("已保存入口", result.primaryLabel());
        assertEquals("", result.description());
        assertEquals("新的标题", service.load().home().title());
        ArgumentCaptor<List<SiteSettingsService.KeyValueRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(settings).updateAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("content", captor.getValue().getFirst().group());
        assertEquals("content.presentation.home", captor.getValue().getFirst().key());
    }
    @Test void footerExplicitFalseAndEmptyStringsSurviveSaveAndRead() throws Exception {
        stored.put("content.presentation.footer", "{\"inheritBrand\":true,\"inheritContact\":true,\"brandTagline\":\"保留此值\"}");
        var form = JSON.readValue("{\"inheritBrand\":false,\"inheritContact\":false,\"noteTitle\":\"\",\"wordmark\":\"\"}", PresentationVO.Footer.class);
        service.saveFooter(form);
        var footer = service.loadFooter();
        assertEquals(false, footer.inheritBrand());
        assertEquals(false, footer.inheritContact());
        assertEquals("", footer.noteTitle());
        assertEquals("", footer.wordmark());
        assertEquals("保留此值", footer.brandTagline());
    }
    @Test void booleanStringsAndNumbersAreRejected() {
        assertThrows(Exception.class, () -> JSON.readValue("{\"inheritBrand\":\"true\"}", PresentationVO.Footer.class));
        assertThrows(Exception.class, () -> JSON.readValue("{\"inheritContact\":1}", PresentationVO.Footer.class));
    }
    @Test void malformedOrUnsafeStoredSectionsFallBackWithoutPublishingUnsafeLinks() {
        stored.put("content.presentation.home", "{\"primaryUrl\":\"javascript:alert(1)\"}");
        stored.put("content.presentation.about", "not json");
        assertEquals("/works", service.loadHome().primaryUrl());
        assertEquals(3, service.loadAbout().principles().size());
    }
    @ParameterizedTest @ValueSource(strings={"javascript:alert(1)","data:text/html,x","//evil.example","/\\evil.example","mqq://join/1","qq://group/1","https://user:pass@example.com","https://exa mple.com","https://example.com\n"})
    void unsafeUrlsAreRejected(String value) { assertFalse(PresentationService.isSafeUrl(value)); }
    @ParameterizedTest @ValueSource(strings={"","/","/works?type=paper","#","#section","https://example.com/path?q=1","http://localhost:8080/"})
    void supportedUrlsAreAccepted(String value) { assertTrue(PresentationService.isSafeUrl(value)); }
    @Test void invalidSaveCannotWriteSettings() throws Exception {
        assertThrows(BusinessException.class, () -> service.saveHome(JSON.readValue("{\"primaryUrl\":\"javascript:x\"}", PresentationVO.Home.class)));
        verify(settings, never()).updateAll(anyList());
    }
    @ParameterizedTest @ValueSource(strings={"title","worksTitle","capabilitiesTitle","directionsTitle","invitationTitle"})
    void requiredHomeTitlesCannotBeCleared(String field) {
        var object = JSON.createObjectNode().put(field, " ");
        assertThrows(BusinessException.class, () -> service.saveHome(JSON.treeToValue(object, PresentationVO.Home.class)));
    }
    @Test void limitsAndMediaModeAreValidated() throws Exception {
        for (int limit : new int[]{0,7}) {
            var object = JSON.createObjectNode().put("featuredLimit", limit);
            assertThrows(BusinessException.class, () -> service.saveHome(JSON.treeToValue(object, PresentationVO.Home.class)));
        }
        assertThrows(BusinessException.class, () -> service.saveHome(JSON.readValue("{\"mediaMode\":\"anything\"}", PresentationVO.Home.class)));
        var join = JSON.createObjectNode().put("groupNumber", "x".repeat(41));
        assertThrows(BusinessException.class, () -> service.saveJoin(JSON.treeToValue(join, PresentationVO.Join.class)));
        var principles = IntStream.range(0,9).mapToObj(i -> new PresentationVO.Principle("条目" + i, "说明")).toList();
        assertThrows(BusinessException.class, () -> service.saveAbout(new PresentationVO.About(null,null,principles,null,null,null,null)));
    }
    @Test void settingsWriteChannelRetainsAfterCommitInvalidation() {
        var mapper = mock(SiteSettingsMapper.class);
        var words = mock(SensitiveWordService.class);
        var cache = mock(CacheHelper.class);
        var actualSettings = new SiteSettingsService(mapper, words, cache);
        actualSettings.updateAll(List.of(new SiteSettingsService.KeyValueRequest("content", "content.presentation.home", "{}")));
        verify(mapper).batchUpsert(anyList());
        verify(cache).evictAfterCommit("cache:site:settings");
        verify(cache).evictByPatternAfterCommit("cache:site:group:*");
    }
}