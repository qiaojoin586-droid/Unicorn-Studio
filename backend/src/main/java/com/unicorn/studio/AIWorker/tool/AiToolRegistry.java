package com.unicorn.studio.AIWorker.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.mapper.AiToolConfigMapper;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 工具层的装配中心。
 * <p>
 * <b>两路来源</b>：
 * <ol>
 *   <li><b>内置工具</b>：代码里的 {@link AiTool} 实现（{@code @Component} 扫到），
 *       描述来自代码、不存在被外部篡改的可能 ⇒ scope 固定 {@code public}
 *       （§11.4："站点用户路径只装配 scope='public' 的工具（即内置只读工具）"）；</li>
 *   <li><b>API 工具</b>：{@code ai_tool_config} 里 {@code tool_type='api'} 的行，
 *       scope 由管理员在后台配 —— <b>"外部工具永不给前台"就是靠这一列执行的</b>。</li>
 * </ol>
 * <p>
 * <b>装配顺序固定（§11.4）</b>：scope 过滤 → priority 升序取前 12 → 跳过熔断中的。
 * ⚠️ 顺序不能换：先过滤再排序，排序才有意义（取舍都是在"候选集"上做的）。
 * <p>
 * <b>被截断必须记 WARN</b>（§11.4 原文：「不要静默截断 —— 否则又是"工具明明配了却不生效"
 * 这类要靠猜的故障」）。同一个"配了却不生效"还有另外两个成因（§11.2 排查顺序）：
 * 总开关 {@code app.ai.tools.enabled} 关着、单工具 {@code enabled=0}。
 * 三者都表现为"模型不调它"，但修法完全不同。
 */
@Component
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    /** 单轮装配上限（§11.4：单轮装配 ≤12，按 priority 显式取舍）。 */
    private static final int MAX_PER_ROUND = 12;

    /** 内置工具的默认优先级，与表里的默认值一致（100）。 */
    private static final int DEFAULT_PRIORITY = 100;

    /** 空的 Schema：工具没声明参数时用，模型传什么都不该报错。 */
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"required\":[]}";

    private final boolean toolsEnabled;
    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    private final AiToolConfigMapper configMapper;
    private final ApiToolInvoker apiInvoker;
    private final ToolCircuitBreaker breaker;
    private final ObjectMapper mapper;
    private final AiToolInvocationMapper auditMapper;

    public AiToolRegistry(List<AiTool> candidates,
                          @Value("${app.ai.tools.enabled:false}") boolean toolsEnabled,
                          AiToolConfigMapper configMapper,
                          ApiToolInvoker apiInvoker,
                          ToolCircuitBreaker breaker,
                          ObjectMapper mapper,
                          AiToolInvocationMapper auditMapper) {
        this.toolsEnabled = toolsEnabled;
        this.configMapper = configMapper;
        this.apiInvoker = apiInvoker;
        this.breaker = breaker;
        this.mapper = mapper;
        this.auditMapper = auditMapper;
        if (candidates != null) {
            for (AiTool tool : candidates) {
                if (!tool.enabled()) {
                    log.info("AI 工具 [{}] 已注册但当前禁用（enabled=false）", name(tool));
                    continue;
                }
                String n = name(tool);
                if (tools.containsKey(n)) {
                    log.warn("AI 工具名称重复 [{}]，后者覆盖前者，请检查实现类命名", n);
                }
                tools.put(n, tool);
            }
        }
        log.info("AI 工具层初始化完成：候选 {} 个，可用 {} 个，装配开关 app.ai.tools.enabled={}",
                candidates == null ? 0 : candidates.size(), tools.size(), toolsEnabled);
    }


    /** 已注册的内置工具名列表（供后台/诊断查看）。 */
    public List<String> registeredNames() {
        return new ArrayList<>(tools.keySet());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 装配给模型的工具回调。
     * <p>
     * <b>总开关关着时返回空数组</b>：Spring AI 的 ChatClient 不会发送 tools 字段，
     * 因此"零工具"状态下的对话行为与纯问答完全一致 —— 这是
     * {@code app.ai.tools.enabled=false} 的一键回退保证（§13 验收项：
     * "app.ai.tools.enabled=false 时行为与今天完全一致"）。
     *
     * @param caller 本轮调用者（决定 scope 过滤与审计归属）
     * @param budget 本轮共享的预算对象（由 {@code AiChatService.stream()} 创建，天然按轮隔离）
     */
    public ToolCallback[] callbacks(AiToolCaller caller, ToolCallBudget budget) {
        if (!toolsEnabled) {
            return new ToolCallback[0];
        }
        // ① 收集两路来源，统一成「候选」再走同一套过滤/排序/上限逻辑
        List<Candidate> candidates = new ArrayList<>();
        for (AiTool tool : tools.values()) {
            candidates.add(new Candidate(tool.definition(), AiToolConfig.SCOPE_PUBLIC,
                    DEFAULT_PRIORITY, adapt(tool)));
        }
        candidates.addAll(apiCandidates(caller));

        // ② scope 过滤（§11.4：外部工具永不给前台）
        List<Candidate> visible = new ArrayList<>();
        for (Candidate c : candidates) {
            if (scopeAllowed(c.scope(), caller.invokerType())) {
                visible.add(c);
            } else {
                log.debug("AI 工具 [{}] 的 scope={} 不适用于调用者 {}，本轮不装配",
                        c.definition().name(), c.scope(), caller.invokerType());
            }
        }

        // ③ priority 升序（同名按工具名兜底，保证顺序稳定、可复现）
        visible.sort(Comparator.comparingInt(Candidate::priority).thenComparing(c -> c.definition().name()));

        // ④ 单轮上限 12：被截断的必须记 WARN（§11.4：不要静默截断）
        List<Candidate> selected = visible;
        if (visible.size() > MAX_PER_ROUND) {
            selected = visible.subList(0, MAX_PER_ROUND);
            log.warn("AI 工具单轮装配被截断：可用 {} 个，仅装配前 {} 个（按 priority 升序），"
                            + "被截断的工具：{}。若这些工具本该被用上，请调低其 priority 或调高上限",
                    visible.size(), MAX_PER_ROUND,
                    visible.subList(MAX_PER_ROUND, visible.size()).stream()
                            .map(c -> c.definition().name()).toList());
        }

        // ⑤ 跳过熔断中的工具（§11.5：熔断期间该工具不装配）
        List<ToolCallback> callbacks = new ArrayList<>(selected.size());
        for (Candidate c : selected) {
            String toolName = c.definition().name();
            if (breaker.isOpen(toolName)) {
                log.warn("AI 工具 [{}] 处于熔断状态（剩余 {}s），本轮不装配",
                        toolName, breaker.remainingSeconds(toolName));
                continue;
            }
            callbacks.add(new BudgetedToolCallback(c.callback(), budget, breaker, mapper, auditMapper, caller));
        }
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * scope 是否适用于该调用者。
     * <p>
     * ⚠️ 口径来自 §11.4：站点用户只装 {@code public}；{@code mcp} 恒仅后台
     * （R17「外部工具永不给前台」）。后台可以装全部 —— 它本来就是管理端。
     */
    private boolean scopeAllowed(String scope, String invokerType) {
        if ("site_user".equals(invokerType)) {
            return AiToolConfig.SCOPE_PUBLIC.equals(scope);
        }
        return true;
    }

    /**
     * 读数据库里配的 API 工具。
     * <p>
     * ⚠️ 查询放在本类而不是 Service：这是<b>装配查询</b>（每次对话调一次、只取启用且已确认的），
     * 不是后台管理列表（要分页、要各种筛选）。项目铁律是"mapper 零查询方法"，
     * 而这张表目前唯一的读者就是本类 —— 为它单开一个 Service 属于过度分层。
     */
    private List<Candidate> apiCandidates(AiToolCaller caller) {
        List<Candidate> result = new ArrayList<>();
        try {
            LambdaQueryWrapper<AiToolConfig> wrapper = new LambdaQueryWrapper<AiToolConfig>()
                    .eq(AiToolConfig::getToolType, AiToolConfig.TYPE_API)
                    .eq(AiToolConfig::getEnabled, 1)
                    // ⚠️ 未确认的工具不装配（§11.4「不可信描述不入提示」）：
                    // 描述会进模型上下文，外部改了描述而没人确认 ⇒ 工具投毒。
                    // 内置工具不走这条路径（描述在代码里，不存在被改）。
                    .isNotNull(AiToolConfig::getConfirmedAt);
            for (AiToolConfig cfg : configMapper.selectList(wrapper)) {
                String schema = cfg.getParamSchema();
                if (schema == null || schema.isBlank()) {
                    // 没写 Schema ⇒ 退化成"接受任意参数"，否则模型传什么都解析不了
                    schema = EMPTY_SCHEMA;
                }
                result.add(new Candidate(
                        new AiToolDefinition(cfg.getToolName(), cfg.getDescription(), schema),
                        cfg.getScope(),
                        cfg.getPriority() == null ? DEFAULT_PRIORITY : cfg.getPriority(),
                        apiCallback(cfg, caller)));
            }
        } catch (Exception e) {
            // ⚠️ 查表失败不能让整轮对话挂掉：退化成"只有内置工具"
            log.error("读取 AI 工具配置失败，本轮只装配内置工具：{}", e.getMessage(), e);
        }
        return result;
    }

    /** API 工具的回调：闭包捕获配置与调用者，执行交给 {@link ApiToolInvoker}。 */
    private ToolCallback apiCallback(AiToolConfig cfg, AiToolCaller caller) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                String schema = cfg.getParamSchema();
                return ToolDefinition.builder()
                        .name(cfg.getToolName())
                        .description(cfg.getDescription())
                        .inputSchema(schema == null || schema.isBlank() ? EMPTY_SCHEMA : schema)
                        .build();
            }

            @Override
            public String call(String argumentsJson) {
                return apiInvoker.invoke(cfg, argumentsJson,
                        caller.conversationId(), caller.invokerType(), caller.invokerId());
            }
        };
    }

    /** 装配候选：描述 + scope + priority（排序，通过定义priority把可靠的工具放前面，减少误选） + 已造好的回调。 */
    private record Candidate(AiToolDefinition definition, String scope, int priority, ToolCallback callback) {
    }

    /** 把与框架无关的工具适配为 Spring AI 的 ToolCallback。 */
    private ToolCallback adapt(AiTool tool) {
        AiToolDefinition def = tool.definition();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(def.name())
                        .description(def.description())
                        .inputSchema(def.inputSchema())
                        .build();
            }

            @Override
            public String call(String argumentsJson) {
                return tool.invoke(argumentsJson);
            }
        };
    }

    private static String name(AiTool tool) {
        AiToolDefinition def = tool.definition();
        return def == null || def.name() == null || def.name().isBlank()
                ? tool.getClass().getSimpleName()
                : def.name();
    }
}
