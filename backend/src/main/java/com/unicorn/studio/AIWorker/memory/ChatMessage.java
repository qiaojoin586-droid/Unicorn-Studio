package com.unicorn.studio.AIWorker.memory;

/**
 * 一条记忆消息（记忆层自有类型，<b>不依赖模型框架</b>）。
 * <p>
 * <b>为什么不直接用 Spring AI 的 {@code Message}</b>：记忆层若依赖框架类型，
 * 将来换模型框架（智谱 → 别的）就得改记忆层。契约用自有类型 ⇒ 换框架时
 * 只有 {@code AiChatService} 里那行映射要动。
 *
 * @param role    user / assistant
 * @param content 消息正文
 */
public record ChatMessage(String role, String content) {

    /** 用户消息的 role 值（与 {@code ai_message.role} 一致）。 */
    public static final String ROLE_USER = "user";

    /** 助手消息的 role 值。 */
    public static final String ROLE_ASSISTANT = "assistant";
}
