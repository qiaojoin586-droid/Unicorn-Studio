package com.unicorn.studio.AIWorker.tool;

/**
 * AI 工具（Function Calling）扩展点 —— 工具层接口。
 * <p>
 * 设计目标：把「模型可调用的能力」与「AI 主流程」解耦。新增一个工具只需：
 * <ol>
 *   <li>实现本接口并用 {@code @Component} 注册（Spring 自动扫描进 {@link AiToolRegistry}）；</li>
 *   <li>在 {@link #definition()} 中声明名称/用途/参数 Schema —— 模型据此判断何时调用；</li>
 *   <li>在 {@link #invoke} 中执行实际逻辑并返回文本结果（结果会作为 Tool 消息回灌给模型）。</li>
 * </ol>
 * 无需改动 Controller / Service / 配置，即可被对话流程自动发现并装配给模型。
 * <p>
 * <b>当前状态</b>：本阶段仅实现最基础的问答（不装配任何工具），工具层作为「预留扩展位」存在；
 * {@link AiToolRegistry} 会注入全部候选工具，装配开关见 {@code app.ai.tools.enabled}。
 */
public interface AiTool {

    /**
     * 工具定义（名称 + 描述 + 参数 JSON Schema）。
     * <p>
     * 名称需全局唯一（建议 {@code 域_动作}，如 {@code work_search}）；
     * 描述是模型选择工具的唯一依据，务必写清「做什么、什么时候用」。
     */
    AiToolDefinition definition();

    /**
     * 执行工具。
     *
     * @param argumentsJson 模型给出的参数（JSON 字符串，Schema 由 {@link #definition()} 声明）
     * @return 供模型消费的文本结果；建议返回结构化 JSON 字符串而非自然语言，便于模型精确引用
     */
    String invoke(String argumentsJson);

    /** 默认启用；返回 false 可用于灰度/临时下线某个工具而不删除代码。 */
    default boolean enabled() {
        return true;
    }
}
