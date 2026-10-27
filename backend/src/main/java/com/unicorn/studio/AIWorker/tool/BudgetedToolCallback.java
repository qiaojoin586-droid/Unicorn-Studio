package com.unicorn.studio.AIWorker.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具回调的装饰器：套上「单轮预算 + 熔断」（文档 §11.5 方案 A）。
 * <p>
 * 真实逻辑全部委托给 {@link #delegate}，本类只负责<b>在入口处做三道判断</b>：
 * <ol>
 *   <li><b>熔断</b>：该工具连续失败 ≥3 次且在 5 分钟熔断期内 ⇒ 不执行；</li>
 *   <li><b>调用次数</b>：本轮已调满 3 次 ⇒ 不执行；</li>
 *   <li><b>累计耗时</b>：本轮已超 15 秒 ⇒ 不执行。</li>
 * </ol>
 * 三道判断都在<b>真正执行之前</b>，所以被拦下时<b>不产生副作用</b>——不会白发一次 HTTP 请求。
 * <p>
 * ⚠️ <b>为什么这些判断不能放在 {@code AiTool.invoke()} 里</b>：那是工具实现自己的代码，
 * 每个工具都得写一遍、且新人加工具时极易漏。放在装饰器里是<b>一次接线、全局生效</b>。
 */
@Slf4j
public class BudgetedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolCallBudget budget;
    private final ToolCircuitBreaker breaker;
    private final ObjectMapper mapper;

    /**
     * 每个工具一个装饰器实例，但<b>共享同一轮的 budget</b>——这是"单轮"的实现关键。
     *
     * @param delegate 真实工具回调
     * @param budget   本轮共享的预算对象（由 AiChatService.stream() 创建）
     * @param breaker  熔断器（Spring 单例）
     */
    public BudgetedToolCallback(ToolCallback delegate, ToolCallBudget budget,
                                ToolCircuitBreaker breaker, ObjectMapper mapper) {
        this.delegate = delegate;
        this.budget = budget;
        this.breaker = breaker;
        this.mapper = mapper;
    }

    /**
     * 返回工具定义，让模型知道这个工具叫啥、参数长啥样。
     */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    /**
     * 唯一需要重写的方法。
     * <p>
     * ⚠️ {@code call(String, ToolContext)} 是 default 方法，字节码验证其末尾
     * {@code invokevirtual call:(String)}——<b>默认委托到本方法</b>，
     * 所以只需实现这一个，不必写两遍。
     */
    @Override
    public String call(String argumentsJson) {
        String toolName = delegate.getToolDefinition().name();

        // ① 熔断：该工具近期连续失败，先摘掉，别让模型在一个坏接口上耗预算
        if (breaker.isOpen(toolName)) {
            // ⚠️ 这条必须留日志：后台只看得到 result_status，
            // 没有这行就分不清「被熔断摘了」和「被预算拦了」——两者修法完全不同
            log.warn("AI 工具 [{}] 处于熔断状态，本轮不再执行", toolName);
            return error(toolName, "circuit_open",
                    "该工具近期连续失败，暂时不可用，请改用其他方式回答", true);
        }
        // ② 次数预算
        if (!budget.tryConsume()) {
            log.warn("AI 工具 [{}] 本轮调用次数已用完（上限 {} 次），不再执行", toolName, budget.maxCalls());
            return error(toolName, "budget_exhausted",
                    "本轮工具调用次数已用完（上限 " + budget.maxCalls()
                            + " 次），请基于已有信息直接作答，不要再请求工具", false);
        }
        // ③ 累计耗时预算
        if (budget.durationExceeded()) {
            log.warn("AI 工具 [{}] 本轮对话已超工具调用时间预算，不再执行", toolName);
            return error(toolName, "budget_exhausted",
                    "本轮对话已超过工具调用时间预算，请基于已有信息直接作答，不要再请求工具", false);
        }

        // ④ 真执行。⚠️ 兜住"工具自己抛的"，但放过程序错误
        try {
            String result = delegate.call(argumentsJson);
            breaker.onSuccess(toolName);
            return result;
        } catch (ToolGuardException e) {
            // 契约化异常：码已定好，直接转 JSON，且不计入熔断（用法问题 ≠ 接口坏了）
            return error(toolName, e.errorCode(), e.getMessage(), false, e.field());
        } catch (IllegalArgumentException | ClassCastException | IndexOutOfBoundsException
                 | NullPointerException codeBug) {
            // ⚠️ 编程错误：不是工具"坏了"，是代码有 bug。计入熔断会把工具误伤5 分钟，
            // 而真正该做的是让它冒到上层记 ERROR 日志。返回错误是为了不崩 SSE，
            // 但【不计入熔断】——熔断是给"上游故障"用的。
            log.error("AI 工具 [{}] 出现编程错误（非上游故障），请检查实现：{}", toolName, codeBug.getMessage(),
                    codeBug);
            return error(toolName, "internal", "工具内部错误，请基于已有信息作答", false);
        } catch (RuntimeException e) {
            breaker.onFailure(toolName);
            return error(toolName, "internal", "工具执行异常，请基于已有信息作答", true);
        }

    }

    /**
     * 生成契约化错误 JSON（《建议》§7.6 + §11.5）。
     * ⚠️ <b>不把异常堆栈放进去</b>：堆栈里可能有 SQL 原文、表名、内部地址。
     * 详细原因走 ai_tool_invocation 与服务端日志，模型只拿到人话。
     */
    private String error(String toolName, String code, String message, boolean retryable) {
        return error(toolName, code, message, retryable, null);
    }

    private String error(String toolName, String code, String message, boolean retryable, String field) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "tool");
            body.put("tool", toolName);
            body.put("error", code);
            body.put("message", message);
            body.put("retryable", retryable);
            if (field != null) {
                body.put("field", field);
            }
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            // 兜底：连错误 JSON 都序列化不出来时返回字面量，保证 SSE 不崩。
            // 但必须记日志——否则"错误提示都发不出去"这件事本身是查不到的。
            log.error("AI 工具 [{}] 的错误信息序列化失败，已降级为字面量：{}", toolName, e.getMessage(), e);
            return "{\"source\":\"tool\",\"error\":\"internal\",\"retryable\":true}";
        }
    }
}
