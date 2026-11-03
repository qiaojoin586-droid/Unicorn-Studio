package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiApiUrlValidator;
import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.dto.AiTestRequest;
import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.crypto.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiConfigServiceTest {
    @Mock private AiConfigMapper configMapper;
    @Mock private AiConfigProvider configProvider;
    @Mock private CryptoService crypto;
    @Mock private AiChatService chatService;
    @Mock private CacheHelper cache;
    @Mock private AiApiUrlValidator apiUrlValidator;
    @InjectMocks private AiConfigService service;

    @BeforeEach
    void savedConfig() {
        when(configProvider.runtimeConfig()).thenReturn(new AiRuntimeConfig(
                "saved-key", "https://open.bigmodel.cn/api/paas/v4", "saved-model", "system", 8, 0.7, false));
    }

    @ParameterizedTest
    @MethodSource("networkFailures")
    void reportsUnderlyingFailureInsteadOfGenericRetryWrapper(Exception cause, String expected) {
        when(chatService.callOnce(any(), anyString())).thenThrow(new RuntimeException("Retry exhausted", cause));

        var result = service.test(null);

        assertFalse(result.success());
        assertTrue(result.message().contains(expected), result.message());
        assertNull(result.reply());
        verifyNoInteractions(configMapper, cache, crypto);
    }

    static Stream<Arguments> networkFailures() {
        return Stream.of(
                Arguments.of(new HttpConnectTimeoutException("HTTP connect timed out"), "连接超时"),
                Arguments.of(new HttpTimeoutException("request timed out"), "请求超时"),
                Arguments.of(new UnknownHostException("unknown.example"), "域名解析失败"),
                Arguments.of(new SSLHandshakeException("handshake failed"), "TLS 连接失败"),
                Arguments.of(new ConnectException("connection refused"), "无法建立连接"),
                Arguments.of(new RuntimeException("401 Unauthorized"), "鉴权失败"),
                Arguments.of(new RuntimeException("429 Too Many Requests"), "供应商限流"));
    }

    @Test
    void savedConfigUsesProbeBudgetEvenWhenAiIsDisabled() {
        when(chatService.callOnce(any(), anyString())).thenReturn("连通");

        assertTrue(service.test(null).success());

        var config = ArgumentCaptor.forClass(AiRuntimeConfig.class);
        verify(chatService).callOnce(config.capture(), anyString());
        assertEquals(512, config.getValue().maxTokens());
        assertTrue(config.getValue().enabled());
        assertEquals("saved-key", config.getValue().apiKey());
    }

    @Test
    void unsavedFormUsesStoredKeyWithoutWritingConfiguration() {
        when(chatService.callOnce(any(), anyString())).thenReturn("连通");

        var result = service.test(new AiTestRequest("", "https://api.deepseek.com/v1", "new-model"));

        assertTrue(result.success());
        var config = ArgumentCaptor.forClass(AiRuntimeConfig.class);
        verify(chatService).callOnce(config.capture(), anyString());
        assertEquals("saved-key", config.getValue().apiKey());
        assertEquals("new-model", config.getValue().model());
        assertEquals("https://api.deepseek.com/v1", config.getValue().apiUrl());
        verifyNoInteractions(configMapper, cache, crypto);
    }
}
