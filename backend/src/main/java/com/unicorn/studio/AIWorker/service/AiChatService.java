package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * AI 对话服务 —— 流式问答的唯一入口。
 * <p>
 * <b>流式契约</b>：返回 {@link Flux}&lt;String&gt;（增量文本分片），由 Controller 转成 SSE。
 * 前端 {@code useAiChat.js} 按 {@code data: <分片>} + 终止帧 {@code [DONE]} 消费，
 * 跨分片行缓冲已在其内部实现，因此后端只需保证「逐片直出、不缓冲、不合并」。
 * <p>
 * <b>工具层接入点</b>：{@link AiToolRegistry#callbacks()} 返回的工具会装配进 ChatClient。
 * 当前阶段开关默认关闭 ⇒ 返回空数组 ⇒ 与纯问答行为完全一致；
 * 后续新增工具无需改动本类。
 * <p>
 * <b>分层说明</b>：本类只做编排（取配置 → 建客户端 → 调模型 → 直出分片），
 * 不理解 Controller、不关心 SSE 帧格式、不直接碰数据库。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiConfigProvider configProvider;
    private final ChatClientFactory clientFactory;
    private final AiToolRegistry toolRegistry;

    public AiChatService(AiConfigProvider configProvider,
                         ChatClientFactory clientFactory,
                         AiToolRegistry toolRegistry) {
        this.configProvider = configProvider;
        this.clientFactory = clientFactory;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 流式对话。
     *
     * @param userMessage 用户输入（已由 Controller 校验非空）
     * @return 增量文本分片流；调用方负责订阅与错误兜底
     * @throws BusinessException 未配置可用模型时抛出（400）
     */
    public Flux<String> stream(String userMessage) {
        AiRuntimeConfig config = configProvider.runtimeConfig();
        if (!config.usable()) {
            log.warn("AI 配置不可用：enabled={}, hasKey={}, url={}, model={}",
                    config.enabled(),
                    config.apiKey() != null && !config.apiKey().isBlank(),
                    config.apiUrl(), config.model());
            throw AiRuntimeConfig.unavailable();
        }

        ToolCallback[] tools = toolRegistry.callbacks();
        ChatClient client = clientFactory.client(config, (Object[]) tools);
        if (tools.length > 0) {
            log.debug("本轮对话装配工具 {} 个：{}", tools.length, toolRegistry.registeredNames());
        }

        return client.prompt()
                .user(userMessage)
                .toolCallbacks(tools)
                .stream()
                .content()
                // 单个分片异常不应中断整条流：记录后跳过，让对话继续（前端已能处理缺失分片）
                .doOnError(e -> log.error("AI 流式响应异常", e));
    }

    /**
     * 非流式对话（供「测试连通性」等需要一次性完整回答的场景使用）。
     *
     * @param config    显式指定的配置（连通性测试用表单值），为 null 时用库中配置
     * @param userMessage 探测消息
     * @return 模型完整回复
     */
    public String callOnce(AiRuntimeConfig config, String userMessage) {
        ChatClient client = clientFactory.client(config);
        return client.prompt().user(userMessage).call().content();
    }

    /** 当前是否具备对话能力（供能力概览接口使用，不抛异常）。 */
    public boolean available() {
        return configProvider.usable();
    }
}
