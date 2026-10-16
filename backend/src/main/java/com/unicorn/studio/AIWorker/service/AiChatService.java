package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.service.SensitiveWordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 命中敏感词时的拒绝文案（文档 §9.1，原文给定，可配置） */
    private static final String BLOCKED_REPLY = "抱歉，该内容不符合平台规范，我无法回答。换个说法我们再聊聊？";

    private final AiConfigProvider configProvider;
    private final ChatClientFactory clientFactory;
    private final AiToolRegistry toolRegistry;
    private final AiSessionService aisessionService;
    private final AiQuotaService aiquotaService;
    private final SensitiveWordService sensitiveWordService;


    public AiChatService(AiConfigProvider configProvider,
                         ChatClientFactory clientFactory,
                         AiToolRegistry toolRegistry,
                         AiSessionService aisessionService,
                         AiQuotaService aiquotaService,
                         SensitiveWordService sensitiveWordService) {
        this.configProvider = configProvider;
        this.clientFactory = clientFactory;
        this.toolRegistry = toolRegistry;
        this.aisessionService = aisessionService;
        this.aiquotaService = aiquotaService;
        this.sensitiveWordService = sensitiveWordService;
    }

    /**
     * 流式对话。
     *
     * @param userMessage 用户输入（已由 Controller 校验非空）
     * @return 增量文本分片流；调用方负责订阅与错误兜底
     * @throws BusinessException 未配置可用模型时抛出（400）
     */
    public Flux<String> stream(String userMessage, String conversationId,
                               Long userId, String clientMsgId) {
        // ⓪ 敏感词阻断（文档 §9.1：必须在调用模型之前）。
        // 命中也要落库（status=blocked + 命中词），否则无法审计"谁问了什么违规内容"。
        // ⚠️ 日志只打命中词、绝不打消息全文（§9.2：聊天内容里会有用户主动输入的密码/密钥）。
        String hit = sensitiveWordService.findSensitive(userMessage);
        if (hit != null) {
            aisessionService.appendUserMessage(conversationId, userId, userMessage,
                    clientMsgId, "blocked", hit);
            // 拒答也要落库：否则用户刷新后只剩一条孤零零的提问，看不出"我问过、被拒了"
            aisessionService.appendBlockedReply(conversationId, userId, BLOCKED_REPLY);
            log.info("AI 提问命中敏感词，已阻断 userId={} conversationId={} word={}", userId, conversationId, hit);
            // 阻断并返回固定文案
            return Flux.just(BLOCKED_REPLY);
        }
        // ①.1 流式计费（文档 §9.1：必须在调用模型之前）
        aiquotaService.consume(userId);
        // ①.2 落 user 行（幂等：同 clientMsgId 重发不插第二条）
        aisessionService.appendUserMessage(conversationId, userId, userMessage, clientMsgId, "completed", null);

        // ② 历史上下文（正序）。注意元素类型是 Spring AI 的 Message，不是本项目的 AiMessage 实体
        List<Message> history = aisessionService.loadContext(conversationId, userId).stream()
                .map(m -> "user".equals(m.getRole())
                        ? (Message) new UserMessage(m.getContent())
                        : (Message) new AssistantMessage(m.getContent()))
                .toList();

        // ③ assistant 占位行
        Long assistantId = aisessionService.startAssistantMessage(conversationId, userId);

        AiRuntimeConfig config = configProvider.runtimeConfig();
        if (!config.usable()) {
            // 配置不可用：占位行别留成「空 completed」，标成 empty 再抛
            aisessionService.finishAssistantMessage(assistantId, conversationId, "", "empty", null);
            throw AiRuntimeConfig.unavailable();
        }

        ToolCallback[] tools = toolRegistry.callbacks();
        ChatClient client = clientFactory.client(config, (Object[]) tools);

        StringBuilder acc = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);

        return client.prompt()
                .messages(history)          // ⚠️ 历史在前
                .user(userMessage)          // ⚠️ 本轮在后
                .toolCallbacks(tools)
                .stream()
                .content()
                .doOnNext(acc::append)      // 边收边攒
                .doOnComplete(() -> {
                    if (finished.compareAndSet(false, true)) {
                        String content = acc.toString();
                        aisessionService.finishAssistantMessage(assistantId, conversationId,
                                content, content.isBlank() ? "empty" : "completed", null);
                    }
                })
                .doOnError(e -> {
                    log.error("AI 流式响应异常", e);
                    if (finished.compareAndSet(false, true)) {
                        aisessionService.finishAssistantMessage(assistantId, conversationId,
                                acc.toString(), "interrupted", null);
                    }
                })
                .doOnCancel(() -> {
                    // 用户点停止 → Controller 里 dispose() → 这里收到 cancel，onComplete/onError 都不触发
                    if (finished.compareAndSet(false, true)) {
                        aisessionService.finishAssistantMessage(assistantId, conversationId,
                                acc.toString(), "interrupted", null);
                    }
                });
    }

    /**
     * 非流式对话（供「测试连通性」等需要一次性完整回答的场景使用）。
     * <p>
     * 用 {@code probeClient} 而非 {@code client}（B-29）：本方法跑在 <b>Tomcat 请求线程</b>上
     * 且完全同步，而「测试连通性」是「先测再存」的按钮、可被反复点击 ——
     * 用对话的 120s 读超时意味着一处填错的地址就能占住一个请求线程两分钟。
     * 探测路径改用 5s/20s 的短超时。
     *
     * @param config    显式指定的配置（连通性测试用表单值），为 null 时用库中配置
     * @param userMessage 探测消息
     * @return 模型完整回复
     */
    public String callOnce(AiRuntimeConfig config, String userMessage) {
        ChatClient client = clientFactory.probeClient(config);
        return client.prompt().user(userMessage).call().content();
    }

    /** 当前是否具备对话能力（供能力概览接口使用，不抛异常）。 */
    public boolean available() {
        return configProvider.usable();
    }
}
