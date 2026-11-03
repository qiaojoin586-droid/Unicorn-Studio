package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
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

    /** 对话：连接 15s / 读 120s —— 要能容忍首字慢的推理模型 */
    private static final Duration CHAT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CHAT_READ_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 连通性探测：连接 5s / 读 <b>20s</b>（B-29）。
     * <p>
     * 探测是同步阻塞调用，跑在 <b>Tomcat 请求线程</b>上（「测试连通性」按钮），
     * 且「先测再存」的设计让它可以被反复点击。用对话的 120s 读超时意味着
     * 一次填错地址就能占住一个请求线程两分钟，连点几次即可占满线程池。
     * 连通性探测不需要等那么久 —— 20s 足够覆盖首字慢的推理模型。
     */
    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_READ_TIMEOUT = Duration.ofSeconds(20);

    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();
    private final ToolCallingManager toolCallingManager;
    private final ProxySelector httpProxy;

    /**
     * 关闭智谱深度思考的策略（见 {@link ZhipuThinking}）。
     * <p>
     * 无状态、只服务本工厂构建的 HTTP 客户端，故直接 new 而非做成 Spring Bean ——
     * 它是「本工厂如何发请求」的实现细节，不是需要被别处复用/替换的服务。
     * 同一个实例同时挂给下面两个 HTTP 客户端，保证两条路径行为一致、日志也只打一次。
     */
    private final ZhipuThinking thinking = new ZhipuThinking();

    public ChatClientFactory(ToolCallingManager toolCallingManager,
                             @Value("${app.ai.http-proxy:}") String httpProxy) {
        this.toolCallingManager = toolCallingManager;
        this.httpProxy = proxySelector(httpProxy);
    }

    /**
     * 按运行时配置构建（或复用）ChatClient。
     * <p>
     * ⚠️ <b>刻意不接受 tools 参数</b>（文档 §11.1 P1 曾指出它是死参数）：工具在<b>请求级</b>装配
     * ——{@code AiChatService.stream()} 里的 {@code .toolCallbacks(tools)}，那是唯一正确的位置（§11.6）。
     * 放这里有两个坏处：① 工具集会被拼进下面的 {@link #fingerprint}，每改一次工具配置就换一个缓存键，
     * {@code MAX_CACHE=8} 会被击穿、客户端反复重建；② 被复用的同一客户端会串用不同请求的工具集。
     *
     * @param config 已解密的运行时配置
     */
    public ChatClient client(AiRuntimeConfig config) {
        return cached(fingerprint(config) + "|chat", config, CHAT_CONNECT_TIMEOUT, CHAT_READ_TIMEOUT, false);
    }

    /**
     * 连通性探测专用客户端：读超时收紧到 20s（B-29）。
     * <p>
     * 与 {@link #client} 共用缓存但指纹带 {@code |probe} 后缀，因此两者互不覆盖 ——
     * 否则管理员点一次「测试连通性」就会把对话用的 120s 客户端挤掉（或反之）。
     */
    public ChatClient probeClient(AiRuntimeConfig config) {
        return cached(fingerprint(config) + "|probe", config, PROBE_CONNECT_TIMEOUT, PROBE_READ_TIMEOUT, true);
    }

    private ChatClient cached(String fingerprint, AiRuntimeConfig config,
                              Duration connectTimeout, Duration readTimeout, boolean probe) {
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

        return cache.computeIfAbsent(fingerprint, key -> build(config, connectTimeout, readTimeout, probe));
    }

    /** 构建并缓存 AI 配置指纹，用于判断是否需要重建模型实例。 */
    private String fingerprint(AiRuntimeConfig config) {
        return config.normalizedBaseUrl() + "|" + config.model() + "|"
                + config.maxTokensOrDefault() + "|" + config.temperatureOrDefault() + "|"
                + Integer.toHexString(config.apiKey() == null ? 0 : config.apiKey().hashCode());
    }

    private ChatClient build(AiRuntimeConfig config, Duration connectTimeout, Duration readTimeout, boolean probe) {
        // 注意 completionsPath：Spring AI 默认拼 /v1/chat/completions，
        // 而智谱等厂商 base 自带 /v4 版本段，必须覆盖该路径，否则 404（详见 AiRuntimeConfig）。
        //
        // 超时必须显式配置（B-29）：OpenAiApi.Builder 没有任何超时入口，只能通过
        // RestClient 的请求工厂设置。此前本类声明了 CONNECT_TIMEOUT / READ_TIMEOUT 两个常量
        // 但【从未传给 builder】—— 死代码，实际行为是 JDK HttpClient 的默认值，即【不超时】。
        // 那比「最长阻塞 120 秒」更糟：填错地址时请求会一直挂着不返回。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(config.normalizedBaseUrl())
                .apiKey(config.apiKey())
                .completionsPath(config.completionsPath())
                .restClientBuilder(restClientBuilder(connectTimeout, readTimeout))
                // 流式对话走 WebClient，必须单独配置（见 webClientBuilder 的说明）
                .webClientBuilder(webClientBuilder(connectTimeout))
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
                // 探测只发一次请求。SDK 默认重试 10 次并指数退避，会远超前端的 30s 超时。
                .retryTemplate(probe ? RetryTemplate.builder().maxAttempts(1).build()
                        : RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .build();

        return ChatClient.builder(model)
                .defaultSystem(config.systemPromptOrDefault())
                .build();
    }

    /**
     * 构造带超时的 RestClient：连接超时给 JDK HttpClient，读超时给请求工厂。
     * <p>
     * 注意这里不能复用 Spring 容器的全局 RestClient.Builder —— 那上面没有超时设置，
     * 会退回「不超时」的默认行为，正是本项要修的问题。
     * <p>
     * 另外挂上 {@link ZhipuThinkingInterceptor}：Spring AI 1.0.9 没有任何透传自定义请求字段的入口
     * （{@code OpenAiChatOptions} 只暴露标准 OpenAI 参数），而关闭智谱深度思考必须靠请求体里的
     * {@code thinking} 字段，只能在这一层改写。该拦截器按域名收窄，非智谱端点原样放行。
     * <p>
     * ⚠️ 本方法覆盖的是<b>非流式</b>调用（连通性探测）。<b>流式对话不走 RestClient</b>，
     * 必须同时配 {@link #webClientBuilder}，否则修复对前台问答完全无效。
     */
    private RestClient.Builder restClientBuilder(Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = httpClient(connectTimeout);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(new ZhipuThinkingInterceptor(thinking));
    }

    /**
     * 构造流式对话用的 WebClient：挂上 {@link ZhipuThinkingConnector} 以关闭智谱深度思考。
     * <p>
     * <b>为什么必须单独配一个</b>：Spring AI 的 {@code OpenAiApi} 同时持有 RestClient 与 WebClient，
     * 且 {@code chatCompletionStream} 走的是 WebClient（已反编译确认）。
     * 只配 RestClient 是本项第一版修复失效的原因 —— 拦截器一次都没被调用，日志里没有任何痕迹。
     * <p>
     * 连接器显式用 JDK HttpClient（本工程不含 reactor-netty，WebClient 的默认连接器本来就是它），
     * 这样连接超时与 RestClient 侧保持一致。
     */
    private WebClient.Builder webClientBuilder(Duration connectTimeout) {
        HttpClient httpClient = httpClient(connectTimeout);
        return WebClient.builder()
                .clientConnector(new ZhipuThinkingConnector(new JdkClientHttpConnector(httpClient), thinking));
    }

    /** 同步探测与流式对话共用代理设置；不修改 JVM 全局网络行为。 */
    private HttpClient httpClient(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (httpProxy != null) {
            builder.proxy(httpProxy);
        }
        return builder.build();
    }

    private static ProxySelector proxySelector(String rawProxy) {
        if (rawProxy == null || rawProxy.isBlank()) {
            return null;
        }
        URI proxy;
        try {
            proxy = URI.create(rawProxy.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("app.ai.http-proxy 格式应为 http://host:port");
        }
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getPort() < 1 || proxy.getPort() > 65535 || proxy.getUserInfo() != null
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty() && !"/".equals(proxy.getRawPath()))
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null) {
            throw new IllegalArgumentException("app.ai.http-proxy 格式应为 http://host:port（不含账号密码或路径）");
        }
        return ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort()));
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
