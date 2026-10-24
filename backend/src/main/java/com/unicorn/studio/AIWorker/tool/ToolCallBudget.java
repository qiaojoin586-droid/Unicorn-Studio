package com.unicorn.studio.AIWorker.tool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮对话的工具调用预算（文档 §11.5：≤3 次调用 / 累计 15 秒）。
 * <p>
 * <b>为什么必须是"每轮一个实例"</b>：Spring AI 的工具调用循环在 {@code DefaultChatClient} 内部
 * （由 {@code ToolCallingManager} 驱动），项目<b>拿不到"这是第几次调用"这个计数点</b>——
 * 直接写"超 3 次就停"没有地方落。
 * <p>
 * 而本类的实例在 {@code AiChatService.stream()} 里创建（业务线程可见的位置），
 * 装配回调时把<b>同一个实例</b>塞进装饰器，于是"这一轮"天然被一个对象圈住，
 * 下一轮自然是另一个对象——<b>不用做清理、不用 ThreadLocal、不会串轮</b>。
 * <p>
 * <b>为什么不用提示词约束"最多调 3 次"</b>（§11.5 方案 C 明确不采用）：
 * 模型对数量约束的遵守不可靠，而这是<b>成本控制</b>手段——不能靠"请模型自觉"。
 */
public class ToolCallBudget {

    /** 最多调用次数 */
    private final int maxCalls;
    /** 最多耗时预算（毫秒）。 */
    private final long maxDurationMillis;

    /** 本轮已调用次数。原子类：模型可能在一轮里连着调多个工具，不要赌单线程。 */
    private final AtomicInteger used = new AtomicInteger(0);
    /** 本轮开始时间。 */
    private final long startMillis = System.currentTimeMillis();

    public ToolCallBudget(int maxCalls, long maxDurationMillis) {
        this.maxCalls = Math.max(1, maxCalls);
        this.maxDurationMillis = maxDurationMillis;
    }

    /** 占用一次调用额度；额度不足返回 false。 */
    public boolean tryConsume() {
        return used.incrementAndGet() <= maxCalls;
    }

    /** 本轮累计耗时是否已超预算。 */
    public boolean durationExceeded() {
        return System.currentTimeMillis() - startMillis > maxDurationMillis;
    }

    /** 本轮已调用次数。 */
    public int usedCalls() {
        return used.get();
    }

    /** 最多调用次数。 */
    public int maxCalls() {
        return maxCalls;
    }
}
