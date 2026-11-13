package com.unicorn.studio.AIWorker.tool;

/**
 * 一次工具调用的调用者上下文（谁调的、关联哪个会话）。
 * <p>
 * <b>为什么提成独立的顶层类</b>：它要被 {@link AiToolRegistry}（装配时决定 scope 与审计归属）
 * 和 {@link BudgetedToolCallback}（写审计时要知道"谁调的"）两边都用。
 * 早先是 {@code AiToolRegistry} 的嵌套 record，嵌套在 registry 里会让装饰器反过来依赖 registry
 * ——依赖方向别扭（工具执行层不该知道"注册表"长什么样）。
 * <p>
 * <b>为什么用 record 而不是三个散参数</b>：这三个值会一路带进每个工具的闭包里，
 * 参数一多就容易传错顺序（都是 Long/String，传错编译器不报错），而顺序错了
 * {@code invokerId} 会被当成 {@code conversationId}——运行期才炸。
 *
 * @param invokerType    site_user / admin
 * @param invokerId      调用者 id
 * @param conversationId 关联会话（后台助手为 null）
 */
public record AiToolCaller(String invokerType, Long invokerId, String conversationId) {

    /** 前台访客。 */
    public static AiToolCaller siteUser(Long invokerId, String conversationId) {
        return new AiToolCaller("site_user", invokerId, conversationId);
    }
}
