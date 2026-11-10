package com.unicorn.studio.AIWorker.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.entity.AiToolInvocation;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;

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
 * ⚠️ <b>为什么这些判断不能放在各工具方法里</b>：那是工具实现自己的代码，
 * 每个工具都得写一遍、且新人加工具时极易漏。放在装饰器里是<b>一次接线、全局生效</b>。
 */
public class BudgetedToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BudgetedToolCallback.class);

    private final ToolCallback delegate;
    private final ToolCallBudget budget;
    private final ToolCircuitBreaker breaker;
    private final ObjectMapper mapper;
    private final AiToolInvocationMapper auditMapper;
    private final AiToolCaller caller;

    /**
     * 每个工具一个装饰器实例，但<b>共享同一轮的 budget</b>——这是"单轮"的实现关键。
     *
     * @param delegate    真实工具回调
     * @param budget      本轮共享的预算对象（由 AiChatService.stream() 创建）
     * @param breaker     熔断器（Spring 单例）
     * @param auditMapper 审计写入（{@code ai_tool_invocation}）
     * @param caller      调用者上下文（审计要记"谁调的"）
     */
    public BudgetedToolCallback(ToolCallback delegate, ToolCallBudget budget,
                                ToolCircuitBreaker breaker, ObjectMapper mapper,
                                AiToolInvocationMapper auditMapper, AiToolCaller caller) {
        this.delegate = delegate;
        this.budget = budget;
        this.breaker = breaker;
        this.mapper = mapper;
        this.auditMapper = auditMapper;
        this.caller = caller;
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
        long start = System.currentTimeMillis();
        // ⚠️ 审计记在【本装饰器】而不是执行器：内置工具不走 ApiToolInvoker，
        // 写在执行器里的话内置工具的调用轨迹就完全查不到（文档 §11.7 要求"每次工具调用一行"）。
        // 这里是所有工具唯一的公共出口 ⇒ 内置与 API 工具都覆盖，且不会写两遍。
        String status;
        String result;

        // ① 熔断：该工具近期连续失败，先摘掉，别让模型在一个坏接口上耗预算
        if (breaker.isOpen(toolName)) {
            // ⚠️ 这条必须留日志：后台只看得到 result_status，
            // 没有这行就分不清「被熔断摘了」和「被预算拦了」——两者修法完全不同
            log.warn("AI 工具 [{}] 处于熔断状态，本轮不再执行", toolName);
            // ⚠️ 审计里映射成 upstream_error：熔断本身就是"上游连续失败"的结果，
            // 它不是 invalid_argument（用法问题）也不是 internal（代码 bug）。
            // 文档 §11.7 的六个值里没有 circuit_open ⇒ 归到最接近的那个，不新增值（表 COMMENT 是定稿的）
            status = AiToolInvocation.UPSTREAM_ERROR;
            result = error(toolName, "circuit_open",
                    "该工具近期连续失败，暂时不可用，请改用其他方式回答", true);
            audit(toolName, argumentsJson, status, result.length(), start);
            return result;
        }
        // ② 次数预算
        if (!budget.tryConsume()) {
            log.warn("AI 工具 [{}] 本轮调用次数已用完（上限 {} 次），不再执行", toolName, budget.maxCalls());
            // ⚠️ 映射成 empty：这次调用【没有产生任何结果】，字面意思就是"结果为空"。
            // 不能记 ok（会让人以为工具正常跑过），也不能记 internal（不是代码 bug）
            status = AiToolInvocation.EMPTY;
            result = error(toolName, "budget_exhausted",
                    "本轮工具调用次数已用完（上限 " + budget.maxCalls()
                            + " 次），请基于已有信息直接作答，不要再请求工具", false);
            audit(toolName, argumentsJson, status, result.length(), start);
            return result;
        }
        // ③ 累计耗时预算
        if (budget.durationExceeded()) {
            log.warn("AI 工具 [{}] 本轮对话已超工具调用时间预算，不再执行", toolName);
            status = AiToolInvocation.EMPTY;
            result = error(toolName, "budget_exhausted",
                    "本轮对话已超过工具调用时间预算，请基于已有信息直接作答，不要再请求工具", false);
            audit(toolName, argumentsJson, status, result.length(), start);
            return result;
        }

        // ④ 真执行。⚠️ 兜住"工具自己抛的"，但放过程序错误
        try {
            result = invokeTool(argumentsJson);
            breaker.onSuccess(toolName);
            status = statusOf(result);
        } catch (ToolGuardException e) {
            // 契约化异常：码已定好，直接转 JSON，且不计入熔断（用法问题 ≠ 接口坏了）
            status = e.errorCode();
            result = error(toolName, e.errorCode(), e.getMessage(), false, e.field());
        } catch (IllegalArgumentException | ClassCastException | IndexOutOfBoundsException
                 | NullPointerException codeBug) {
            // ⚠️ 编程错误：不是工具"坏了"，是代码有 bug。计入熔断会把工具误伤5 分钟，
            // 而真正该做的是让它冒到上层记 ERROR 日志。返回错误是为了不崩 SSE，
            // 但【不计入熔断】——熔断是给"上游故障"用的。
            log.error("AI 工具 [{}] 出现编程错误（非上游故障），请检查实现：{}", toolName, codeBug.getMessage(),
                    codeBug);
            // ⚠️ 记 upstream_error：枚举里没有 internal（§11.7 定稿的只有六个值），
            // 编程错误的真正定位靠上面那条带堆栈的 ERROR 日志
            status = AiToolInvocation.UPSTREAM_ERROR;
            result = error(toolName, "internal", "工具内部错误，请基于已有信息作答", false);
        } catch (RuntimeException e) {
            breaker.onFailure(toolName);
            // ⚠️ 同上：枚举里没有 internal，暂归 upstream_error
            status = AiToolInvocation.UPSTREAM_ERROR;
            result = error(toolName, "internal", "工具执行异常，请基于已有信息作答", true);
        }
        audit(toolName, argumentsJson, status, result.length(), start);
        return result;
    }

    private String invokeTool(String argumentsJson) {
        if (!(delegate instanceof MethodToolCallback)) {
            return delegate.call(argumentsJson);
        }
        String input = argumentsJson == null || argumentsJson.isBlank() || "null".equals(argumentsJson.trim())
                ? "{}" : argumentsJson;
        try {
            return delegate.call(input);
        } catch (NumberFormatException | ArithmeticException e) {
            // Spring AI 在调用方法前转换整数参数。
            throw ToolGuardException.invalidArgument(null, "请按工具定义传入整数参数");
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof JsonProcessingException) {
                throw ToolGuardException.invalidArgument(null, "工具参数必须是合法的 JSON 对象");
            }
            throw e;
        }
    }

    /**
     * 从工具返回的 JSON 里判断结果状态。
     * <p>
     * ⚠️ 契约化的错误 JSON 里有 {@code error} 字段，成功返回里没有 ⇒ 靠它区分成功/失败。
     * 失败时直接用那个错误码（invalid_argument / timeout / upstream_error / forbidden
     * 与审计表的枚举同源），成功则记 ok。
     * <p>
     * ⚠️ 内置工具"查到了但没有数据"（返回 {@code found:false}）记的是 ok ——
     * 工具<b>正常执行了</b>，只是结果里没有内容，这与"被预算拦下压根没执行"（empty）不是一回事。
     */
    private String statusOf(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return AiToolInvocation.EMPTY;
        }
        try {
            var node = mapper.readTree(resultJson);
            var err = node.get("error");
            if (err != null && !err.isNull() && err.isTextual()) {
                String code = err.asText();
                // ⚠️ 只认审计表枚举里有的值，避免把 circuit_open / budget_exhausted / internal
                // 这类不在枚举里的码直接写进 result_status（列的 COMMENT 是定稿的六个值）
                return switch (code) {
                    case AiToolInvocation.INVALID_ARGUMENT, AiToolInvocation.TIMEOUT,
                         AiToolInvocation.UPSTREAM_ERROR, AiToolInvocation.FORBIDDEN -> code;
                    // ⚠️ 其余（含 internal / circuit_open / budget_exhausted）统一归 upstream_error：
                    // 文档 §11.7 定稿的枚举里没有 internal ⇒ "编程错误"只能先记成 upstream_error。
                    // 真正的定位不靠这张表 —— 那类错误同时会打一条带完整堆栈的 ERROR 日志。
                    default -> AiToolInvocation.UPSTREAM_ERROR;
                };
            }
        } catch (Exception ignored) {
            // 不是 JSON：算异常结果
            return AiToolInvocation.UPSTREAM_ERROR;
        }
        return AiToolInvocation.OK;
    }

    /**
     * 写一行审计（{@code ai_tool_invocation}）。
     * <p>
     * ⚠️ <b>审计写失败绝不能影响主流程</b>：工具已经执行完、结果已经要发给模型了，
     * 不能因为一行审计写不进去就让整轮对话失败。失败只记 ERROR 日志。
     * <p>
     * ⚠️ {@code message_id} 留空：流式下工具调用发生在 assistant 消息落库之前，
     * 那时拿不到 id ⇒ 由 {@code AiSessionService.finishAssistantMessage} 之后回填。
     */
    private void audit(String toolName, String argumentsJson, String status, int resultBytes, long start) {
        if (auditMapper == null || caller == null) {
            return;
        }
        try {
            AiToolInvocation row = new AiToolInvocation();
            row.setConversationId(caller.conversationId());
            row.setInvokerType(caller.invokerType());
            row.setInvokerId(caller.invokerId());
            row.setToolName(toolName);
            row.setArgsDigest(AiToolSupport.digest(argumentsJson, mapper));
            row.setResultStatus(status);
            // ⚠️ 记【模型实际收到的长度】而不是上游原始大小：这一列的用途是
            // 观测"大返回值"（§11.7），而大返回值影响的是 token 成本，
            // 也就是模型实际吃进去多少字节。上游原始大小由返回 JSON 里的 truncated 标记体现。
            row.setResultBytes(resultBytes);
            row.setDurationMs((int) (System.currentTimeMillis() - start));
            auditMapper.insert(row);
        } catch (Exception e) {
            log.error("AI 工具 [{}] 审计写入失败（该次调用将无法在后台追溯）：{}", toolName, e.getMessage(), e);
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
