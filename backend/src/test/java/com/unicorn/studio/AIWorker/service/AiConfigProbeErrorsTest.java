package com.unicorn.studio.AIWorker.service;

import com.sun.net.httpserver.HttpServer;
import com.unicorn.studio.AIWorker.config.AiApiUrlValidator;
import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.crypto.CryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.web.client.ResourceAccessException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConfigProbeErrorsTest {
    static Stream<Object[]> failures() {
        return Stream.of(
                new Object[]{new HttpConnectTimeoutException("HTTP connect timed out"), "10 秒内未完成 TCP/TLS"},
                new Object[]{new HttpTimeoutException("request timed out"), "20 秒内未收到完整响应"},
                new Object[]{new UnknownHostException("api.example.com"), "域名解析失败"},
                new Object[]{new SSLHandshakeException("certificate error"), "TLS 连接失败"},
                new Object[]{new ConnectException("Connection refused"), "无法建立连接"},
                new Object[]{new RuntimeException("401 Unauthorized"), "鉴权失败（401）"},
                new Object[]{new RuntimeException("429 rate limit"), "供应商限流（429）"}
        );
    }

    @ParameterizedTest
    @MethodSource("failures")
    void distinguishesNestedNetworkAndUpstreamFailures(Exception cause, String message) {
        var provider = mock(AiConfigProvider.class);
        var chat = mock(AiChatService.class);
        var service = new AiConfigService(mock(AiConfigMapper.class), provider, mock(CryptoService.class),
                chat, mock(CacheHelper.class), mock(AiApiUrlValidator.class));
        when(provider.runtimeConfig()).thenReturn(new AiRuntimeConfig("secret-not-for-client", "https://api.example.com/v1",
                "test", "", 512, 0.5, true));
        when(chat.callOnce(any(), anyString())).thenThrow(new RuntimeException("Retry exhausted",
                new ResourceAccessException("I/O error", new IOException("wrapped", cause))));
        var result = service.test(null);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains(message).doesNotContain("secret-not-for-client");
        assertThat(result.apiUrl()).isEqualTo("https://api.example.com/v1");
        verify(chat, times(1)).callOnce(any(), anyString());
    }

    @Test
    void probeDoesNotRetryFailedProviderRequest() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var factory = new ChatClientFactory(ToolCallingManager.builder().build(), "");
            var config = new AiRuntimeConfig("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test", "", 512, 0.5, true);
            assertThatThrownBy(() -> factory.probeClient(config).prompt().user("连通").call().content())
                    .isInstanceOf(Exception.class);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationReleasesRealHttpWorkerWhileWaitingForHeadersOrBody(boolean partialBody) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                if (partialBody) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{\"choices\":[".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var factory = new ChatClientFactory(ToolCallingManager.builder().build(), "");
        var config = new AiRuntimeConfig("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test", "", 512, 0.5, true);
        var task = new FutureTask<Void>(() -> {
            try {
                factory.probeClient(config).prompt().user("连通").call().content();
            } catch (Exception expected) {
                // 取消会以中断或 I/O 异常结束；必须验证线程真的退出，而不只检查 Future 状态。
            } finally {
                finished.countDown();
            }
            return null;
        });
        var worker = new Thread(task, "probe-cancellation-test");
        worker.start();
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            task.cancel(true);
            assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            task.cancel(true);
            release.countDown();
            server.stop(0);
            worker.join(3000);
        }
    }
}
