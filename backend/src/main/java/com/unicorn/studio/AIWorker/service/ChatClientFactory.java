package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 工厂 —— 把「数据库里的配置」变成「可用的 ChatClient」。
 * <p>
 * <b>为什么需要工厂？</b>Spring AI 的 starter 会基于 {@code application.yml} 自动装配一个
 * 全局单例 {@code OpenAiChatModel}，但本项目的 key/url/model 存在数据库且后台可随时修改，
 * 因此这里按需构建模型实例，而不是依赖自动装配。
 * <p>
 * <b>缓存策略</b>：以「apiUrl + model + apiKey 指纹」为键缓存 ChatClient，
 * 避免每轮对话都重建（构建包含连接池与序列化器，代价不低）；
 * 一旦后台改配置，指纹变化自然切到新实例，旧实例在容量达到上限时被整体淘汰，无需显式清理。
 */
@Component
public class ChatClientFactory {

    /**
     * 实例缓存上限。
     * <p>
     * 取值偏保守（8）是刻意的：每个 value 是一个完整的 {@code OpenAiChatModel} + {@code OpenAiApi}，
     * 各自持有 HTTP 客户端资源（连接池、socket、fd），单个实例的开销远不止一个 Java 对象。
     * 而正常使用下并发配置基本不变、命中率极高，上限低不会影响命中率，
     * 只在「管理员连续改多轮配置」时才触发整体淘汰 —— 那种场景下重建几个实例完全可以接受。
     */
    private static final int MAX_CACHE = 8;

    /** 连接超时与读超时：对话场景要能容忍首字慢的推理模型 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();
    private final ToolCallingManager toolCallingManager;

    public ChatClientFactory(ToolCallingManager toolCallingManager) {
        this.toolCallingManager = toolCallingManager;
    }

    /**
     * 按运行时配置构建（或复用）ChatClient。
     *
     * @param config 已解密的运行时配置
     * @param tools  需要装配的工具回调（当前阶段为空数组）
     */
    public ChatClient client(AiRuntimeConfig config, Object... tools) {
        String fingerprint = fingerprint(config);

        ChatClient cached = cache.get(fingerprint);
        if (cached != null) {
            return cached;
        }

        // ⚠️ 淘汰必须在 computeIfAbsent 【之外】完成。
        // ConcurrentHashMap 的 Javadoc 明确规定：mapping function 不得修改此 map。
        // 在 mapping function 内执行 clear() 会：① 该 bin 的锁与其他线程的更新互相等待，存在死锁风险；
        // ② 其他线程并发写入的条目被无声丢弃；③ 某些 JDK 版本下直接抛 IllegalStateException。
        // 触发条件是「缓存达到上限 + 又来一个新指纹」，即管理员改过几轮 AI 配置后必然踩到。
        evictIfOversized();

        return cache.computeIfAbsent(fingerprint, key -> build(config));
    }

    /** 构建并缓存 AI 配置指纹，用于判断是否需要重建模型实例。 */
    private String fingerprint(AiRuntimeConfig config) {
        return config.normalizedBaseUrl() + "|" + config.model() + "|"
                + config.maxTokensOrDefault() + "|" + config.temperatureOrDefault() + "|"
                + Integer.toHexString(config.apiKey() == null ? 0 : config.apiKey().hashCode());
    }

    private ChatClient build(AiRuntimeConfig config) {
        // 注意 completionsPath：Spring AI 默认拼 /v1/chat/completions，
        // 而智谱等厂商 base 自带 /v4 版本段，必须覆盖该路径，否则 404（详见 AiRuntimeConfig）。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.normalizedBaseUrl())
                .apiKey(config.apiKey())
                .completionsPath(config.completionsPath())
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .maxTokens(config.maxTokensOrDefault())
                .temperature(config.temperatureOrDefault())
                .build();

        ChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .build();

        return ChatClient.builder(model)
                .defaultSystem(config.systemPromptOrDefault())
                .build();
    }

    /**
     * 简单的容量控制：超过上限时清空缓存。
     * 配置变更本就低频，与其实现精细 LRU，不如整体清空（下一次请求重建，代价可控且实现简单无 bug 面）。
     */
    private void evictIfOversized() {
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
    }
}
