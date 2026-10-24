package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
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
import java.util.Optional;
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

    /** 撞键重放时，上一次回答还没生成/没完成时给用户的提示（文档 §4.5 v1.2） */
    private static final String REPLAY_IN_PROGRESS = "该消息正在生成，请稍候。";

    private final AiConfigProvider configProvider;
    private final ChatClientFactory clientFactory;
    private final AiToolRegistry toolRegistry;
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
                         AiSessionService aisessionService,
                         AiQuotaService aiquotaService,
                         SensitiveWordService sensitiveWordService,
                         @Value("${app.ai.tools.budget.max-calls:3}") int toolMaxCallsPerRound,
                         @Value("${app.ai.tools.budget.max-duration-millis:15000}") long toolMaxDurationMillis) {
        this.configProvider = configProvider;
        this.clientFactory = clientFactory;
        this.toolRegistry = toolRegistry;
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
        Long newMessageId = aisessionService.appendUserMessage(conversationId, userId, userMessage,
                clientMsgId, "completed", null);
        if (newMessageId == null) {
            // ⓪' 撞键：这条 clientMsgId 之前已经受理过 ⇒【不再调模型、不再调工具】，
            // 直接重放上次的回答（文档 §4.5 v1.2「撞键后的行为」）。
            // ⚠️ 必须在这里 return：往下走会重复调模型、重复调工具（烧真实外部 API 的钱）、
            // 再插一行 assistant，前端也会看到两份回答。
            return replayExisting(conversationId, userId, clientMsgId);
        }

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

        // 工具装配（文档 §11.5 方案 A）：
        // ⚠️ 预算对象在【每轮】这里新建 —— Spring AI 的调用循环在 DefaultChatClient 内部，
        // 拿不到"第几次调用"，只能靠"每轮一个对象"把计数圈住。
        // 下一轮自然是另一个对象 ⇒ 不用清理、不会串轮。
        ToolCallBudget budget = new ToolCallBudget(toolMaxCallsPerRound, toolMaxDurationMillis);
        ToolCallback[] tools = toolRegistry.callbacks(
                new AiToolRegistry.Caller(AiToolInvocation.INVOKER_SITE_USER, userId, conversationId), budget);
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
     * 撞键重放（文档 §4.5 v1.2「撞键后的行为」）。
     * <p>
     * <b>为什么要把已存的内容重新切片推出去</b>：前端只认 SSE 帧（{@code data: <分片>}），
     * 不认"直接给完整答案"。所以这里把已存的回答切片后逐片 yield，
     * 让前端的表现和正常流式完全一致（前端只管把分片拼起来）。
     * <p>
     * <b>⚠️ 重放不算新一轮</b>：不消耗单轮预算、不写新的 assistant 行、不产生工具调用与审计。
     *
     * @return 重放内容的分片流；该消息还在生成中时返回一句提示
     */
    private Flux<String> replayExisting(String conversationId, Long userId, String clientMsgId) {
        Optional<AiMessage> existing = aisessionService.findExistingAssistant(conversationId, userId, clientMsgId);
        if (existing.isEmpty()) {
            // 还没有 assistant 行 ⇒ 上一次还在生成中（文档：「仍在生成中则提示"该消息正在生成"」）
            log.info("AI 消息撞幂等键但回答尚未生成，返回「正在生成」提示 clientMsgId={}", clientMsgId);
            return Flux.just(REPLAY_IN_PROGRESS);
        }
        AiMessage answer = existing.get();
        if (!"completed".equals(answer.getStatus())) {
            // 有行但不是 completed ⇒ 上一次被中断或失败。
            // ⚠️ 不能拿半截内容当答案重放（模型当时可能只吐了半句），也不能说"正在生成"（它已经停了）——
            // 统一按"未完成"提示，让用户知道这条没答完。
            log.info("AI 消息撞幂等键但上次回答未完成（status={}），返回「未完成」提示 clientMsgId={}",
                    answer.getStatus(), clientMsgId);
            return Flux.just(REPLAY_IN_PROGRESS);
        }
        String content = answer.getContent();
        log.info("AI 消息撞幂等键，重放已有回答 clientMsgId={} contentLength={}", clientMsgId, content.length());
        // 分片后逐片返回
        return Flux.fromIterable(splitForReplay(content));
    }

    /**
     * 把已存内容切成 SSE 分片。
     * <p>
     * ⚠️ 切片长度与正常流式不同没有影响：前端只负责拼接，只要拼回去的内容一致就行。
     */
    private List<String> splitForReplay(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }
        int step = 64;
        for (int i = 0; i < content.length(); i += step) {
            chunks.add(content.substring(i, Math.min(content.length(), i + step)));
        }
        return chunks;
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
