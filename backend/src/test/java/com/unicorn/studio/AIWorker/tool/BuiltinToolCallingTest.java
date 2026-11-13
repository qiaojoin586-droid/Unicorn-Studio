package com.unicorn.studio.AIWorker.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import com.unicorn.studio.AIWorker.mapper.AiToolConfigMapper;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import com.unicorn.studio.AIWorker.service.ChatClientFactory;
import com.unicorn.studio.AIWorker.tool.impl.CurrentTimeTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuiltinToolCallingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiToolConfigMapper configMapper = mock(AiToolConfigMapper.class);
    private final AiToolInvocationMapper auditMapper = mock(AiToolInvocationMapper.class);

    @Test
    void discoversCurrentTimeToolAndEnablesToolsByDefault() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        assertThat(scanner.findCandidateComponents(CurrentTimeTool.class.getPackageName()).stream()
                .map(definition -> definition.getBeanClassName())).contains(CurrentTimeTool.class.getName());
        var environment = configuration();
        when(configMapper.selectList(any())).thenReturn(List.of());
        var registry = registry(environment.getRequiredProperty("app.ai.tools.enabled", Boolean.class));
        assertThat(registry.callbacks(AiToolCaller.siteUser(1L, "session"), new ToolCallBudget(3, 15000)))
                .extracting(callback -> callback.getToolDefinition().name()).containsExactly("current_time");
    }

    @Test
    void respectsExplicitGlobalDisable() throws Exception {
        var environment = configuration().withProperty("AI_TOOLS_ENABLED", "false");
        assertThat(registry(environment.getRequiredProperty("app.ai.tools.enabled", Boolean.class))
                .callbacks(AiToolCaller.siteUser(1L, "session"), new ToolCallBudget(3, 15000))).isEmpty();
    }

    @Test
    void respectsAdministratorDisablingAnIndividualBuiltin() {
        var disabled = new AiToolConfig();
        disabled.setToolName("current_time");
        disabled.setToolType(AiToolConfig.TYPE_BUILTIN);
        disabled.setEnabled(0);
        when(configMapper.selectList(any())).thenReturn(List.of(disabled), List.of());
        assertThat(registry(true).callbacks(AiToolCaller.siteUser(1L, "session"), new ToolCallBudget(3, 15000)))
                .isEmpty();
    }

    @Test
    void executesBuiltinDuringStreamingAndReturnsItsResultToModel() throws Exception {
        when(configMapper.selectList(any())).thenReturn(List.of());
        var budget = new ToolCallBudget(3, 15000);
        var callbacks = registry(true).callbacks(AiToolCaller.siteUser(1L, "session"), budget);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requests.add(mapper.readTree(exchange.getRequestBody()));
                String response = requests.size() == 1 ? """
                        data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_time","type":"function","function":{"name":"current_time","arguments":"{}"}}]},"finish_reason":null}]}

                        data: {"id":"test","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                        data: [DONE]

                        """ : """
                        data: {"id":"test2","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"工具调用成功"},"finish_reason":null}]}

                        data: {"id":"test2","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var config = new AiRuntimeConfig("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "test-model", "测试工具调用", 128, 0.0, true);
            var factory = new ChatClientFactory(ToolCallingManager.builder().build(), "");
            List<String> chunks = factory.client(config).prompt().user("现在几点？").toolCallbacks(callbacks)
                    .stream().content().collectList().block(Duration.ofSeconds(10));
            assertThat(String.join("", chunks)).isEqualTo("工具调用成功");
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst().path("tools").get(0).path("function").path("name").asText())
                    .isEqualTo("current_time");
            JsonNode toolMessage = null;
            for (JsonNode message : requests.get(1).path("messages")) {
                if (message.path("role").asText().equals("tool")) {
                    toolMessage = message;
                }
            }
            assertThat(toolMessage).isNotNull();
            assertThat(toolMessage.path("tool_call_id").asText()).isEqualTo("call_time");
            assertThat(mapper.readTree(toolMessage.path("content").asText()).path("datetime").asText())
                    .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
            assertThat(budget.usedCalls()).isEqualTo(1);
            var audit = ArgumentCaptor.forClass(AiToolInvocation.class);
            verify(auditMapper).insert(audit.capture());
            assertThat(audit.getValue().getResultStatus()).isEqualTo(AiToolInvocation.OK);
        } finally {
            server.stop(0);
        }
    }

    private AiToolRegistry registry(boolean enabled) {
        return new AiToolRegistry(List.of(new CurrentTimeTool()), enabled, configMapper, null,
                new ToolCircuitBreaker(3, Duration.ofMinutes(5)), mapper, auditMapper);
    }

    private MockEnvironment configuration() throws Exception {
        var environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }
}
