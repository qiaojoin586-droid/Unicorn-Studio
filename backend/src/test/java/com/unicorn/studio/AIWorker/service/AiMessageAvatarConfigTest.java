package com.unicorn.studio.AIWorker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.config.AiApiUrlValidator;
import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiMessageAvatar;
import com.unicorn.studio.AIWorker.controller.AiConfigController;
import com.unicorn.studio.AIWorker.controller.AiChatController;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.AIWorker.dto.AiConfigUpdateRequest;
import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.admin.service.FileService;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.crypto.CryptoService;
import com.unicorn.studio.model.SiteSettings;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.UploadFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiMessageAvatarConfigTest {
    private final AiConfigMapper mapper = mock(AiConfigMapper.class);
    private final CacheHelper cache = mock(CacheHelper.class);
    private final AiConfigService service = new AiConfigService(mapper, mock(AiConfigProvider.class),
            mock(CryptoService.class), mock(AiChatService.class), cache, mock(AiApiUrlValidator.class));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void defaultsAndRestoresWithoutDiscardingTheCustomPicture() {
        assertThat(AiMessageAvatar.from(Map.of()).effectiveUrl()).isEmpty();
        var saved = Map.of(AiMessageAvatar.KEY_MODE, "default", AiMessageAvatar.KEY_URL, "/uploads/avatar.png");
        assertThat(AiMessageAvatar.from(saved).url()).isEqualTo("/uploads/avatar.png");
        assertThat(AiMessageAvatar.from(saved).effectiveUrl()).isEmpty();
        service.save(form("default", null));
        assertThat(savedSettings()).containsExactlyEntriesOf(Map.of(AiMessageAvatar.KEY_MODE, "default"));
    }

    @Test
    void savesOnlyAvatarFieldsAndInvalidatesItsCache() {
        service.save(form("custom", "/uploads/avatar.png"));
        assertThat(savedSettings()).containsExactlyInAnyOrderEntriesOf(Map.of(
                AiMessageAvatar.KEY_MODE, "custom", AiMessageAvatar.KEY_URL, "/uploads/avatar.png"));
        verify(cache).evictAfterCommit(AiMessageAvatar.CACHE_KEY);
        verify(cache).evictAfterCommit(AiConfigProvider.CACHE_KEY);
    }

    @Test
    void oldClientsDoNotOverwriteAvatarOrApiKey() throws Exception {
        var request = new ObjectMapper().readValue("{\"model\":\"updated-model\"}", AiConfigUpdateRequest.class);
        service.save(request);
        assertThat(savedSettings()).containsExactlyEntriesOf(Map.of(AiConfigProvider.KEY_MODEL, "updated-model"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "//example.com/a.png", "data:image/svg+xml,bad",
            "/uploads/../../private.png", "/uploads/%2e%2e/private.png", "https://user@example.com/a.png"})
    void rejectsUnsafeAvatarAddressesBeforeWriting(String url) {
        assertThatThrownBy(() -> service.save(form("custom", url))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> service.save(form("unknown", ""))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void appearanceCacheContainsOnlyPublicPresentationFields() throws Exception {
        var snapshot = mock(CacheHelper.Snapshot.class);
        when(cache.snapshot(AiMessageAvatar.CACHE_KEY)).thenReturn(snapshot);
        when(snapshot.get(AiMessageAvatar.class)).thenReturn(Optional.empty());
        when(mapper.findByGroupName(AiConfigProvider.GROUP)).thenReturn(List.of(
                SiteSettings.builder().settingKey(AiMessageAvatar.KEY_MODE).settingValue("custom").build(),
                SiteSettings.builder().settingKey(AiMessageAvatar.KEY_URL).settingValue("/uploads/avatar.png").build(),
                SiteSettings.builder().settingKey(AiConfigProvider.KEY_API_KEY).settingValue("private-test-key").build()));
        var provider = new AiConfigProvider(mapper, mock(CryptoService.class), cache);
        AiMessageAvatar avatar = provider.messageAvatar();
        assertThat(avatar.effectiveUrl()).isEqualTo("/uploads/avatar.png");
        assertThat(new ObjectMapper().writeValueAsString(avatar)).doesNotContain("private-test-key", "api_key");
        verify(snapshot).set(eq(avatar), any());
    }

    @Test
    void avatarUploadUsesTheCurrentAdministratorAndExistingFileStorage() {
        var files = mock(FileService.class);
        var controller = new AiConfigController(service, null, files, null);
        var administrator = new SysUser();
        administrator.setId(9L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(administrator, null));
        var image = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});
        var saved = UploadFile.builder().url("/uploads/avatar.png").fileName("avatar.png").size(3L).build();
        when(files.upload(image, 9L)).thenReturn(saved);
        assertThat(controller.uploadMessageAvatar(image).getData()).containsEntry("url", "/uploads/avatar.png");
        verify(files).upload(image, 9L);
    }

    @Test
    void avatarUploadRejectsDocumentsAndOversizedImages() {
        var files = mock(FileService.class);
        var controller = new AiConfigController(service, null, files, null);
        var document = new MockMultipartFile("file", "avatar.pdf", "application/pdf", new byte[]{1});
        assertThatThrownBy(() -> controller.uploadMessageAvatar(document)).isInstanceOf(BusinessException.class);
        var oversized = mock(org.springframework.web.multipart.MultipartFile.class);
        when(oversized.getOriginalFilename()).thenReturn("avatar.png");
        when(oversized.getSize()).thenReturn(5L * 1024 * 1024 + 1);
        assertThatThrownBy(() -> controller.uploadMessageAvatar(oversized)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }

    @Test
    void exposesOnlyTheEffectiveAvatarThroughAnonymousCapabilities() throws Exception {
        var chat = mock(AiChatService.class);
        var registry = mock(AiToolRegistry.class);
        when(chat.messageAvatarUrl()).thenReturn("/uploads/avatar.png");
        when(registry.registeredNames()).thenReturn(List.of("current_time"));
        var controller = new AiChatController(chat, registry, mock(AiSessionService.class));
        var json = new ObjectMapper().valueToTree(controller.capabilities());
        assertThat(json.path("data").path("messageAvatarUrl").asText()).isEqualTo("/uploads/avatar.png");
        assertThat(json.path("data").path("available").asBoolean()).isFalse();
        assertThat(json.toString()).doesNotContain("apiKey", "apiUrl", "systemPrompt", "messageAvatarMode");
    }

    private AiConfigUpdateRequest form(String mode, String url) {
        return new AiConfigUpdateRequest(null, null, null, null, null, null, null, mode, url);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, String> savedSettings() {
        ArgumentCaptor<List<SiteSettings>> rows = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper).batchUpsert(rows.capture());
        return rows.getValue().stream().collect(Collectors.toMap(SiteSettings::getSettingKey, SiteSettings::getSettingValue));
    }
}
