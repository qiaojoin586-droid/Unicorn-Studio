package com.unicorn.studio.AIWorker.service;

import com.sun.net.httpserver.HttpServer;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChatClientFactoryTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> target = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void probeDoesNotRetrySupplierErrors() {
        respond(503, "application/json", "{\"error\":{\"message\":\"temporarily unavailable\"}}");
        var client = factory("").probeClient(config(localUrl()));

        assertThrows(RuntimeException.class, () -> client.prompt().user("test").call().content());
        assertEquals(1, requests.get(), "一次连通性测试只能发起一次上游请求");
    }

    @Test
    void probeDoesNotRetryAuthenticationFailures() {
        respond(401, "application/json", "{\"error\":{\"message\":\"invalid api key\"}}");
        var client = factory("").probeClient(config(localUrl()));

        assertThrows(RuntimeException.class, () -> client.prompt().user("test").call().content());
        assertEquals(1, requests.get());
    }

    @Test
    void configuredProxyCarriesProbeWithoutResolvingTargetLocally() {
        respond(200, "application/json", completion());
        var client = factory(localUrl()).probeClient(config("http://ai-provider.invalid/v4"));

        assertEquals("连通", client.prompt().user("test").call().content());
        assertEquals("http://ai-provider.invalid/v4/chat/completions", target.get());
        assertTrue(body.get().contains("probe-model"));
        assertEquals(1, requests.get());
    }

    @Test
    void configuredProxyAlsoCarriesStreamingChat() {
        String chunk = "{\"id\":\"chunk-1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                + "\"model\":\"probe-model\",\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"连通\"},\"finish_reason\":null}]}";
        respond(200, "text/event-stream", "data: " + chunk + "\n\ndata: [DONE]\n\n");
        var client = factory(localUrl()).client(config("http://ai-provider.invalid/v4"));

        List<String> chunks = client.prompt().user("test").stream().content()
                .collectList().block(Duration.ofSeconds(5));
        assertNotNull(chunks);
        assertEquals("连通", String.join("", chunks));
        assertEquals("http://ai-provider.invalid/v4/chat/completions", target.get());
        assertEquals(1, requests.get());
    }

    private ChatClientFactory factory(String proxy) {
        return new ChatClientFactory(ToolCallingManager.builder().build(), proxy);
    }

    private AiRuntimeConfig config(String url) {
        return new AiRuntimeConfig("test-key", url, "probe-model", "test", 512, 0.7, true);
    }

    private String localUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(int status, String contentType, String response) {
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            target.set(exchange.getRequestURI().toString());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private String completion() {
        return "{\"id\":\"reply-1\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"probe-model\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"连通\"},\"finish_reason\":\"stop\"}]}";
    }
}
