package com.unicorn.studio.AIWorker.controller;

import com.unicorn.studio.AIWorker.dto.ChatRequest;
import com.unicorn.studio.AIWorker.service.AiChatService;
import com.unicorn.studio.AIWorker.vo.AiCapabilitiesVO;
import com.unicorn.studio.AIWorker.tool.AiToolRegistry;
import com.unicorn.studio.model.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台 AI 助手接口（公开，走 /api/** 公开过滤器链 + 既有 Redis 限流）。
 * <p>
 * <b>协议契约（与前端 useAiChat.js 对齐，勿随意更改）</b>：
 * <pre>
 *   POST /api/ai/chat  Content-Type: application/json  {"message":"..."}
 *   → text/event-stream
 *   data: 分片1
 *   data: 分片2
 *   ...
 *   data: [DONE]
 * </pre>
 * 前端以「空行」为事件边界、跨分片做行缓冲，因此这里必须：
 * ① 逐片直出（不累积、不合并）；② 结束发 {@code [DONE]}；③ 出错也要发 {@code [DONE]} 收尾，
 * 否则前端 reader 会一直等待导致 loading 不结束。
 * <p>
 * 采用 {@link SseEmitter} 而非返回 {@code Flux}，是为了与项目已有的 Servlet MVC 栈（非 WebFlux）
 * 保持一致，并显式控制超时与生命周期。
 */
@RestController
@RequestMapping("/api/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    /** SSE 超时：覆盖「慢推理模型首字延迟 + 长回答」的最坏情况 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    /** 流结束标记（前端据此 finalize 消息） */
    private static final String DONE = "[DONE]";

    private final AiChatService chatService;
    private final AiToolRegistry toolRegistry;

    public AiChatController(AiChatService chatService, AiToolRegistry toolRegistry) {
        this.chatService = chatService;
        this.toolRegistry = toolRegistry;
    }

    /** 流式问答。 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean finished = new AtomicBoolean(false);

        // 用户关闭页面/网络中断时，取消上游订阅，避免「无人消费仍在生成」浪费 token
        emitter.onCompletion(() -> finished.set(true));
        emitter.onTimeout(() -> {
            finished.set(true);
            emitter.complete();
        });
        emitter.onError(e -> finished.set(true));

        Disposable subscription;
        try {
            subscription = chatService.stream(request.message()).subscribe(
                    chunk -> sendQuietly(emitter, chunk),
                    error -> {
                        log.error("AI 流式对话失败", error);
                        sendQuietly(emitter, friendlyMessage(error));
                        completeQuietly(emitter, finished);
                    },
                    () -> completeQuietly(emitter, finished));
        } catch (RuntimeException e) {
            // 配置不可用等「启动即失败」的情况：同步给出提示后正常收尾
            log.warn("AI 对话启动失败：{}", e.getMessage());
            sendQuietly(emitter, friendlyMessage(e));
            completeQuietly(emitter, finished);
            return emitter;
        }

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
        return emitter;
    }

    /** 能力概览：供前端展示「助手是否在线」以及已装配的工具。 */
    @GetMapping("/capabilities")
    public ApiResponse<AiCapabilitiesVO> capabilities() {
        return ApiResponse.ok(new AiCapabilitiesVO(
                AiCapabilitiesVO.ASSISTANT_NAME,
                chatService.available(),
                true,
                toolRegistry.registeredNames(),
                null));
    }

    /** 发送分片；对端已断开时忽略异常（属正常路径，不刷错误日志）。 */
    private void sendQuietly(SseEmitter emitter, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送失败（客户端可能已断开）：{}", e.getMessage());
        }
    }

    /** 发送结束标记并 complete，且保证只执行一次。 */
    private void completeQuietly(SseEmitter emitter, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(DONE));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    /** 把内部异常翻译成对用户友好的提示（不泄露堆栈、不暴露内部地址）。 */
    private String friendlyMessage(Throwable error) {
        String msg = error.getMessage() == null ? "" : error.getMessage();
        if (msg.contains("尚未完成配置")) {
            return "AI 助手尚未完成配置，请联系管理员在后台开启";
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试。";
    }
}
