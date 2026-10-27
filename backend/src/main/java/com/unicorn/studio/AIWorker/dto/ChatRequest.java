package com.unicorn.studio.AIWorker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 前台对话请求 DTO。
 * <p>
 * 与历史 {@code model.ChatMessage} 字段兼容（{@code message}），
 * 保证前端 {@code useAiChat.js} 无需改动即可继续工作。
 *
 * @param message 用户本轮输入
 */
public record ChatRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 2000, message = "消息长度不能超过 2000 字")
        String message,

        /** 会话 id；为空时后端自动建一个（兼容老前端） */
        String sessionId,

        /** 前端生成的幂等键，必填。crypto.randomUUID() 即可 */
        @NotBlank(message = "clientMsgId 缺失")
        @Size(max = 40)
        String clientMsgId
) {
}
