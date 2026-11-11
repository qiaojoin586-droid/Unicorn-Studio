package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.memory.ChatMessage;
import com.unicorn.studio.AIWorker.memory.ChatMemoryProvider;
import com.unicorn.studio.AIWorker.tool.AiToolCaller;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.AIWorker.tool.ToolCallBudget;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.service.SensitiveWordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
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
    private final ChatMemoryProvider memoryProvider;
    private final AiSessionService aisessionService;
    private final AiQuotaService aiquotaService;
    private final SensitiveWordService sensitiveWordService;

    /** 单轮工具调用次数上限（§11.5：≤3 次） */
    private final int toolMaxCallsPerRound;
    /** 单轮工具调用累计耗时上限（§11.5：15 秒） */
    private final long toolMaxDurationMillis;


    public AiChatService(AiConfigProvider configProvider,
                         ChatClientFactory clientFactory,
                         AiToolRegistry toolRegistry,
                         ChatMemoryProvider memoryProvider,
                         AiSessionService aisessionService,
                         AiQuotaService aiquotaService,
                         SensitiveWordService sensitiveWordService,
                         @Value("${app.ai.tools.budget.max-calls:3}") int toolMaxCallsPerRound,
                         @Value("${app.ai.tools.budget.max-duration-millis:15000}") long toolMaxDurationMillis) {
        this.configProvider = configProvider;
        this.clientFactory = clientFactory;
        this.toolRegistry = toolRegistry;
        this.memoryProvider = memoryProvider;
        this.aisessionService = aisessionService;
        this.aiquotaService = aiquotaService;
        this.sensitiveWordService = sensitiveWordService;
        this.toolMaxCallsPerRound = toolMaxCallsPerRound;
        this.toolMaxDurationMillis = toolMaxDurationMillis;
    }

    /**
     * 流式对话。
     *
     * @param userMessage 用户输入（已由 Controller 校验非空）
     * @return 增量文本分片流；调用方负责订阅与错误兜底
     * @throws BusinessException 未配置可用模型时抛出（400）
     */
    public Flux<String> stream(String userMessage, String requestedConversationId,
                               Long userId, String clientMsgId) {
        final String conversationId;
        if (requestedConversationId == null || requestedConversationId.isBlank()) {
            conversationId = aisessionService.createSession(userId, userMessage).id();
        } else {
            aisessionService.requireOwned(requestedConversationId, userId);
            conversationId = requestedConversationId;
        }
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
        // 在本轮消息落库前读取历史，否则本轮提问会同时出现在 history 和 .user() 中。
        List<ChatMessage> context = new ArrayList<>(memoryProvider.loadContext(conversationId, userId));
        // ①.2 落 user 行（幂等：同 clientMsgId 重发不插第二条）
        Long newMessageId = aisessionService.appendUserMessage(conversationId, userId, userMessage,
                clientMsgId, "completed", null);
        if (newMessageId == null) {
            // ⓪' 撞键：这条 clientMsgId 之前已经受理过 ⇒ user 行不再重复插。
            // ⚠️ 这里【不返回、继续往下走】：当作一次新的对话正常生成。
            //   · 不覆盖旧行 —— 旧的半截 assistant 行留着，新结果另起一行；
            //     列表与上下文都是按 id 倒序取（recentForContext / pageByCursor 都 ORDER BY id DESC），
            //     新的在前、旧的半截行沉在下面。
            //   · 半截行不再进上下文（recentForContext 只取 status='completed'），
            //     所以模型看不到"我上次只答了一半"，不会困惑或重复啰嗦。
            // ⚠️ 曾试过"返回「该消息正在生成」"：那是个死局 —— 占位行的 status 与被中断的行
            //   都是 interrupted，分不出"还在跑"和"早就死了"，用户重发一百次都只得到同一句提示。
            log.info("AI 消息撞幂等键，按新对话继续生成 conversationId={} clientMsgId={}",
                    conversationId, clientMsgId);
            // 中断后的重试：末尾可能已保留同一条用户消息，只从本次 prompt 中去重。
            if (!context.isEmpty()) {
                ChatMessage last = context.get(context.size() - 1);
                if (ChatMessage.ROLE_USER.equals(last.role()) && userMessage.equals(last.content())) {
                    context.remove(context.size() - 1);
                }
            }
        }

        // ② 历史上下文（正序）。⚠️ 走 ChatMemoryProvider 而不是直接调 SessionService：
        //   记忆的截断策略将来可插拔（换实现类即可），这里只负责映射成框架的 Message。
        //   ⚠️ 映射这一步留在本层：记忆层的自有类型 ChatMessage 不依赖模型框架，
        //   将来换模型框架时只有这一行要动。
        List<Message> history = context.stream()
                .map(m -> ChatMessage.ROLE_USER.equals(m.role())
                        ? (Message) new UserMessage(m.content())
                        : (Message) new AssistantMessage(m.content()))
                .toList();

        // ③ assistant 占位行
        Long assistantId = aisessionService.startAssistantMessage(conversationId, userId);

        AiRuntimeConfig config = configProvider.runtimeConfig();
        if (!config.usable()) {
            // 配置不可用：占位行别留成「空 completed」，标成 empty 再抛
            aisessionService.finishAssistantMessage(assistantId, conversationId, "", "empty", null);
            throw AiRuntimeConfig.unavailable();
        }

        // 工具装配（文档 §11.5 方案 A）：
        // ⚠️ 预算对象在【每轮】这里新建 —— Spring AI 的调用循环在 DefaultChatClient 内部，
        // 拿不到"第几次调用"，只能靠"每轮一个对象"把计数圈住。
        // 下一轮自然是另一个对象 ⇒ 不用清理、不会串轮。
        ToolCallBudget budget = new ToolCallBudget(toolMaxCallsPerRound, toolMaxDurationMillis);
        ToolCallback[] tools = toolRegistry.callbacks(
                AiToolCaller.siteUser(userId, conversationId), budget);
        // §11.7 第 6 条：装配清单也要有落点，否则无法区分「工具没生效」与「模型没选它」
        // （前者查配置/代码，后者改描述措辞，两类故障修法完全不同）。
        // 这行只含工具名与调用者 id，不含任何用户消息内容，与 §9.2 不冲突。
        log.info("AI 本轮装配工具：invokerType=site_user invokerId={} budget={}/{} 个 tools={}",
                userId, budget.usedCalls(), budget.maxCalls(), toolNames(tools));
        ChatClient client = clientFactory.client(config);

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
     * 装配上的工具名列表（只含名字，不含任何用户内容，与 §9.2 不冲突）。
     */
    private List<String> toolNames(ToolCallback[] tools) {
        List<String> names = new ArrayList<>(tools.length);
        for (ToolCallback t : tools) {
            names.add(t.getToolDefinition().name());
        }
        return names;
    }

    /**
     * 非流式对话（供「测试连通性」等需要一次性完整回答的场景使用）。
     * <p>
     * 用 {@code probeClient} 而非 {@code client}：连通性测试在独立的有界线程池中调用本方法，
     * 连接（含 TLS 握手）10s、请求20s，不自动重试，不调用工具，不写入聊天历史。
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

    public String messageAvatarUrl() {
        return configProvider.messageAvatar().effectiveUrl();
    }
}
