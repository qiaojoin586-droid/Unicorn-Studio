package com.unicorn.studio.AIWorker.memory;

import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.service.AiSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认记忆提供者：沿用现有的「取最近 N 条参与上下文的消息」。
 * <p>
 * ⚠️ <b>为什么轮数要 ×2</b>：配置项的单位是「轮」（{@code max-turns}），
 * 而底层 SQL 取的是「条」。一轮 = 1 问 + 1 答 = 2 条 ⇒ 取 {@code maxTurns * 2} 条。
 * <p>
 * ⚠️ <b>为什么不做精确配对</b>：一轮里可能有多条消息（工具调用、拒答行），
 * 精确按轮分组要写复杂 SQL，而实际差异最多一条 ⇒ 多带一条不会出事，
 * <b>不值得为它增加 SQL 复杂度</b>。
 */
@Component
public class DefaultChatMemoryProvider implements ChatMemoryProvider {

    private final AiSessionService sessionService;
    private final int maxTurns;

    public DefaultChatMemoryProvider(AiSessionService sessionService,
                                     @Value("${app.ai.memory.max-turns:10}") int maxTurns) {
        this.sessionService = sessionService;
        this.maxTurns = Math.max(1, maxTurns);
    }

    @Override
    public List<ChatMessage> loadContext(String conversationId, Long userId) {
        int limit = maxTurns * 2;
        List<ChatMessage> result = new ArrayList<>();
        for (AiMessage m : sessionService.loadContext(conversationId, userId, limit)) {
            if (m.getRole() == null || m.getContent() == null || m.getContent().isBlank()) {
                // ⚠️ 空内容（占位行）不进模型 —— 白占窗口名额
                continue;
            }
            result.add(new ChatMessage(m.getRole(), m.getContent()));
        }
        return result;
    }

    /** 配置的轮数上限（供后台展示与排查用）。 */
    public int maxTurns() {
        return maxTurns;
    }
}
