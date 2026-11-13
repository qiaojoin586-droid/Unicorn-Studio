package com.unicorn.studio.AIWorker.vo;

import com.unicorn.studio.AIWorker.entity.AiConversation;

import java.time.LocalDateTime;

/** 后台会话列表项。只给摘要字段，不含消息正文（§6.5 硬约束 1）。 */
public record AdminSessionVO(
        String id,
        Long userId,
        String title,
        Integer messageCount,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt
) {
    public static AdminSessionVO from(AiConversation conv) {
        return new AdminSessionVO(conv.getId(), conv.getUserId(), conv.getTitle(),
                conv.getMessageCount(), conv.getLastActiveAt(), conv.getCreatedAt());
    }
}
