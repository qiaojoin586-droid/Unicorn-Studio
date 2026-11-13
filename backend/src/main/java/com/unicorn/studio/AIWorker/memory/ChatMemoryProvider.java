package com.unicorn.studio.AIWorker.memory;

import java.util.List;

/**
 * 记忆上下文提供者（文档 §10 / R14 的预留契约）。
 * <p>
 * <b>本期不实现任何新的记忆策略</b>，但把接口留出来 ⇒ 将来做记忆时
 * <b>改动范围 = 1 个实现类 + 1 行装配 + 1 个配置</b>，不改表、不改流程。
 * <p>
 * <b>为什么不用 {@code default} 方法直接给个空实现</b>：那样"忘了实现"会静默返回空列表，
 * 而记忆为空是<b>静默的功能失效</b>（模型突然失忆，没人报错）⇒ 这里要求实现类必须显式写。
 * <p>
 * ⚠️ <b>与文档的一处偏离</b>：文档写「本期恒为空列表 ⇒ 与现状行为一致」。
 * 实际<b>现状并不是空的</b> —— 功能②（会话落库）已经把最近若干条历史喂给模型了
 * （{@code AiSessionService.loadContext}）。若照文档字面实现成"恒返回空"，
 * 模型会从"能记住前文"退化成"完全失忆"，那是功能倒退。
 * ⇒ 所以默认实现<b>沿用现有行为</b>，只是把它挪到接口后面。
 */
public interface ChatMemoryProvider {

    /**
     * 取这次对话要带进模型的历史。
     *
     * @param conversationId 会话
     * @param userId         归属（<b>防越权，务必校验</b>）
     * @return 供模型消费的历史消息，按时间正序；无历史时返回空列表
     */
    List<ChatMessage> loadContext(String conversationId, Long userId);
}
