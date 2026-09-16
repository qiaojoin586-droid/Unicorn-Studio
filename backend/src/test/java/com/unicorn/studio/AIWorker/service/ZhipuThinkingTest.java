package com.unicorn.studio.AIWorker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关闭智谱深度思考的回归测试。
 * <p>
 * 之所以值得单独写测试：本项修的是「HTTP 200 + 空 content ⇒ 前端显示『没有收到有效回复』」这个
 * <b>全程无异常、无日志</b>的故障。它一旦失效，表现是「AI 偶尔不回话」——几乎无法从日志反查。
 * <p>
 * 用例分三组：
 * <ol>
 *   <li><b>WebClient 端到端</b>（本类最重要的用例）：起一个本地 HTTP 服务，让 WebClient 真的把
 *       请求发出去，断言服务端收到的字节里带上了 {@code thinking}。
 *       这条用例专门防「只配了 RestClient」——那个错误在单元测试里完全看不出来，
 *       本次故障的第一版修复正是栽在这里（拦截器一次都没被调用）；</li>
 *   <li><b>RestClient</b>：非流式路径（连通性探测）同样要改写；</li>
 *   <li><b>该原样放行的</b>：非智谱域名、已显式指定 thinking、非 JSON、空体 —— 同样重要，
 *       给 OpenAI 官方端点塞私有字段会直接被 400 拒绝。</li>
 * </ol>
 */
class ZhipuThinkingTest {

    private static final String ZHIPU_CN = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String ZHIPU_INTL = "https://api.z.ai/api/paas/v4/chat/completions";
    private static final String OPENAI = "https://api.openai.com/v1/chat/completions";

    /** 贴近 Spring AI 实际发出的请求体 */
    private static final String CHAT_BODY = """
            {"messages":[{"role":"user","content":"解释一下什么是java泛型"}],\
            "model":"GLM-4.5-Air","max_tokens":512,"temperature":0.7,"stream":true}""";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 默认策略（仅智谱域名生效）；测试里另用 127.0.0.1 版本以便打到本地服务 */
    private final ZhipuThinking zhipu = new ZhipuThinking();

    // ==================== 第一组：WebClient 端到端（流式对话的真实路径） ====================

    @Test
    @DisplayName("WebClient 端到端：发到智谱的流式请求体里真的带上了 thinking.type=disabled")
    void webClientActuallyPatchesStreamingRequestBody() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startServer(received);
        try {
            String response = webClient(List.of("127.0.0.1"))
                    .post()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ChatRequestBody.of("GLM-4.5-Air", 512, "解释一下什么是java泛型"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            assertEquals("data: [DONE]\n\n", response, "SSE 响应应原样透传（改写不能影响响应侧）");
            assertNotNull(received.get(), "本地服务没收到请求");
            Map<String, Object> json = parse(received.get());
            assertEquals(Map.of("type", "disabled"), json.get("thinking"),
                    "流式请求体没有被改写 —— 前台对话仍会空回复。"
                            + "（Spring AI 的流式走 WebClient，只配 RestClient 无效）");
            // 原始字段必须完好
            assertEquals("GLM-4.5-Air", json.get("model"));
            assertEquals(512, ((Number) json.get("max_tokens")).intValue());
            assertEquals(true, json.get("stream"));
            assertNotNull(json.get("messages"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("WebClient 端到端：非智谱域名一个字节都不改")
    void webClientLeavesOtherVendorsUntouched() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startServer(received);
        try {
            // 策略只认 example.com ⇒ 打到 127.0.0.1 的本地服务不应被改写
            webClient(List.of("example.com"))
                    .post()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ChatRequestBody.of("gpt-4o", 512, "你好"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            assertFalse(received.get().contains("thinking"),
                    "非智谱端点被塞了私有字段，会被对方直接 400");
        } finally {
            server.stop(0);
        }
    }

    // ==================== 第二组：RestClient（非流式，连通性探测） ====================

    @Test
    @DisplayName("RestClient 端到端：改写后的请求体真的发得出去（Content-Length 必须同步）")
    void restClientActuallySendsPatchedBody() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startServer(received);
        try {
            RestClient client = RestClient.builder()
                    .requestFactory(new JdkClientHttpRequestFactory())
                    .requestInterceptor(new ZhipuThinkingInterceptor(new ZhipuThinking(List.of("127.0.0.1"))))
                    .build();

            // ⚠️ 这条断言的价值在于「不抛异常」。Spring 的编码器会按原始长度设好 Content-Length，
            //    改写后若不同步，JDK 客户端在真正发送时才报错（后台「测试连通性」整条链路 500）：
            //    「Too many bytes in request body. Expected: 334, got: 365」
            //    ——用 MockClientHttpRequest 的单测<b>测不出来</b>，必须走真实 HTTP。
            String response = client.post()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(CHAT_BODY)
                    .retrieve()
                    .body(String.class);

            assertNotNull(response, "请求没发出去");
            Map<String, Object> json = parse(received.get());
            assertEquals(Map.of("type", "disabled"), json.get("thinking"));
            assertEquals("GLM-4.5-Air", json.get("model"));
            assertEquals(512, ((Number) json.get("max_tokens")).intValue());
            assertNotNull(json.get("messages"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("RestClient：智谱国际站（api.z.ai）同样生效")
    void restClientPatchesZhipuIntl() throws IOException {
        Map<String, Object> json = parse(new String(intercept(ZHIPU_INTL, CHAT_BODY), StandardCharsets.UTF_8));
        assertEquals(Map.of("type", "disabled"), json.get("thinking"));
    }

    // ==================== 第三组：该原样放行的 ====================

    @Test
    @DisplayName("RestClient：非智谱域名原样发出")
    void restClientLeavesOtherVendorsUntouched() throws IOException {
        byte[] sent = intercept(OPENAI, CHAT_BODY);
        assertArrayEquals(CHAT_BODY.getBytes(StandardCharsets.UTF_8), sent);
        assertFalse(new String(sent, StandardCharsets.UTF_8).contains("thinking"));
    }

    @Test
    @DisplayName("域名伪装：notbigmodel.cn / bigmodel.cn.evil.com 不匹配")
    void doesNotMatchLookalikeHosts() {
        assertFalse(zhipu.isZhipuHost(URI.create("https://notbigmodel.cn/v1/chat/completions")));
        assertFalse(zhipu.isZhipuHost(URI.create("https://bigmodel.cn.evil.com/v1/chat/completions")));
        assertTrue(zhipu.isZhipuHost(URI.create("https://open.bigmodel.cn/api/paas/v4")));
        assertTrue(zhipu.isZhipuHost(URI.create("https://bigmodel.cn/x")));
    }

    @Test
    @DisplayName("调用方已显式指定 thinking：尊重它，不覆盖")
    void respectsExplicitThinking() throws IOException {
        String body = "{\"model\":\"GLM-4.5-Air\",\"thinking\":{\"type\":\"enabled\"}}";
        Map<String, Object> json = parse(new String(intercept(ZHIPU_CN, body), StandardCharsets.UTF_8));
        assertEquals(Map.of("type", "enabled"), json.get("thinking"));
    }

    @Test
    @DisplayName("非 JSON / 空请求体：原样放行且不抛异常（优化参数不该把对话打废）")
    void passesThroughUnparsableBody() throws IOException {
        assertArrayEquals("not-json".getBytes(StandardCharsets.UTF_8), intercept(ZHIPU_CN, "not-json"));
        assertArrayEquals(new byte[0], intercept(ZHIPU_CN, ""));
    }

    @Test
    @DisplayName("无主机名的 URI 不匹配，且不抛异常")
    void handlesUriWithoutHost() {
        assertEquals(null, zhipu.patch(CHAT_BODY.getBytes(StandardCharsets.UTF_8), URI.create("file:/tmp/x.json")));
        assertFalse(zhipu.isZhipuHost(URI.create("file:/tmp/x.json")));
    }

    // ==================== 辅助 ====================

    private WebClient webClient(List<String> hosts) {
        return WebClient.builder()
                .clientConnector(new ZhipuThinkingConnector(new JdkClientHttpConnector(), new ZhipuThinking(hosts)))
                .build();
    }

    /** 起一个本地服务，记录收到的请求体，并回一段最小 SSE */
    private HttpServer startServer(AtomicReference<String> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 跑一遍 RestClient 拦截器，返回真正发给上游的请求体 */
    private byte[] intercept(String url, String body) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create(url));
        AtomicReference<byte[]> sent = new AtomicReference<>();
        ClientHttpRequestExecution execution = (req, reqBody) -> {
            sent.set(reqBody);
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        new ZhipuThinkingInterceptor(zhipu).intercept(request, body.getBytes(StandardCharsets.UTF_8), execution);
        assertNotNull(sent.get(), "拦截器未调用下游执行链");
        return sent.get();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) throws IOException {
        return objectMapper.readValue(json, Map.class);
    }

    /** 模拟 Spring AI 传给 WebClient 的请求体对象（走真实序列化，而不是预先拼好的 JSON 串） */
    private record ChatRequestBody(String model, int max_tokens, List<Map<String, String>> messages, boolean stream) {

        static ChatRequestBody of(String model, int maxTokens, String question) {
            return new ChatRequestBody(model, maxTokens,
                    List.of(Map.of("role", "user", "content", question)), true);
        }
    }
}
