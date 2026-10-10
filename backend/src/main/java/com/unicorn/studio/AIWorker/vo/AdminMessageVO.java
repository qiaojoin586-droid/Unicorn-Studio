package com.unicorn.studio.AIWorker.vo;

import com.unicorn.studio.AIWorker.entity.AiMessage;

import java.time.LocalDateTime;

/**
 * 后台消息项。列表接口进来的 content 是 SQL 截断后的摘要；详情接口是全文。
 * deleted 由 deleted_at 是否为空推出 —— 后台不过滤软删除，靠这个标记告诉前端。
 */
public record AdminMessageVO(
        Long id,
        String conversationId,
        Long userId,
        String role,
        String content,
        String status,
        String blockedWord,
        Integer tokenUsage,
        boolean deleted,
        LocalDateTime createdAt
) {
    public static AdminMessageVO from(AiMessage m) {
        return new AdminMessageVO(m.getId(), m.getConversationId(), m.getUserId(),
                m.getRole(), m.getContent(), m.getStatus(), m.getBlockedWord(),
                m.getTokenUsage(), m.getDeletedAt() != null, m.getCreatedAt());
    }
}
