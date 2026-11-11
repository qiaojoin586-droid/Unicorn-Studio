package com.unicorn.studio.AIWorker.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.entity.AiToolConfig;
import com.unicorn.studio.AIWorker.mapper.AiToolConfigMapper;
import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 工具层的装配中心。
 * <p>
 * <b>两路来源</b>：
 * <ol>
 *   <li><b>内置工具</b>：代码里的 {@link AiTool} Bean，其 {@code @Tool} 方法转为回调，
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

    /**
     * 分层配额（§11.4「工具数量分层配额」）。
     * <p>
     * ⚠️ <b>为什么 API 工具要限 5 个</b>：每个工具的描述都会进模型上下文，
     * 工具越多模型选错的概率越高、每次调用的 token 成本也越高。
     * <p>
     * ⚠️ <b>为什么内置要限 15 个</b>：内置工具由代码维护，数量可控，但"以后有人加 20 个内置工具"
     * 就会把上下文撑爆 ⇒ 留一道防线（超了只记日志，不静默丢）。
     */
    private static final int MAX_BUILTIN = 15;
    private static final int MAX_API = 5;

    /** 内置工具的默认优先级，与表里的默认值一致（100）。 */
    private static final int DEFAULT_PRIORITY = 100;

    /** 空的 Schema：工具没声明参数时用，模型传什么都不该报错。 */
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"required\":[]}";

    private final boolean toolsEnabled;
    private final List<ToolCallback> builtinTools;

    private final AiToolConfigMapper configMapper;
    private final ApiToolInvoker apiInvoker;
    private final ToolCircuitBreaker breaker;
    private final ObjectMapper mapper;
    private final AiToolInvocationMapper auditMapper;

    public AiToolRegistry(List<AiTool> candidates,
                          @Value("${app.ai.tools.enabled:true}") boolean toolsEnabled,
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
        this.builtinTools = candidates.isEmpty() ? List.of() : List.of(ToolCallbacks.from(candidates.toArray()));
        log.info("AI 工具层初始化完成：内置工具 {} 个，装配开关 app.ai.tools.enabled={}",
                builtinTools.size(), toolsEnabled);
    }

    /** 后台同步使用与模型调用相同的定义。 */
    public List<ToolDefinition> builtinDefinitions() {
        return builtinTools.stream().map(ToolCallback::getToolDefinition).toList();
    }

    /** 已注册的内置工具名列表（供后台/诊断查看）。 */
    public List<String> registeredNames() {
        return builtinDefinitions().stream().map(ToolDefinition::name).toList();
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
        // ⚠️ 内置工具在表里的配置一次查完（每个工具查一次表 ⇒ 每轮 6~7 次多余查询）
        Map<String, AiToolConfig> builtinCfg = builtinConfig();
        for (ToolCallback tool : builtinTools) {
            String name = tool.getToolDefinition().name();
            AiToolConfig cfg = builtinCfg.get(name);
            // ⚠️ 表里的 enabled 也要生效：管理员在后台停用了某个内置工具 ⇒ 后端就不该再装配它。
            // ⚠️ 以前这里没判 ⇒ 后台的开关改了没反应（表里的 enabled 对内置工具是摆设）。
            // ⚠️ 表里没有这一行（同步没跑过 / 表被清空）⇒ 默认启用（代码为准），
            //    否则一次查表失败就让所有内置工具集体消失。
            if (cfg != null && cfg.getEnabled() != null && cfg.getEnabled() != 1) {
                log.info("AI 工具 [{}] 已被后台停用（ai_tool_config.enabled=0），本轮不装配", name);
                continue;
            }
            // ⚠️ priority 也读表里的：运营在后台调优先级对内置工具也要有效
            int priority = cfg != null && cfg.getPriority() != null ? cfg.getPriority() : DEFAULT_PRIORITY;
            candidates.add(new Candidate(tool, AiToolConfig.SCOPE_PUBLIC, priority));
        }
        if (candidates.size() > MAX_BUILTIN) {
            log.warn("AI 内置工具数量 {} 超过配额 {}（§11.4），本轮只装配前 {} 个",
                    candidates.size(), MAX_BUILTIN, MAX_BUILTIN);
            candidates = new ArrayList<>(candidates.subList(0, MAX_BUILTIN));
        }
        List<Candidate> apiList = apiCandidates(caller);
        if (apiList.size() > MAX_API) {
            // ⚠️ API 工具超配额 ⇒ 按 priority 取前 N 个，并明确告知管理员
            // （不静默丢：被丢掉的工具在后台列表里仍能看到，但本轮没装配）
            log.warn("AI API 工具数量 {} 超过配额 {}（§11.4），本轮只装配前 {} 个：被截断的工具：{}",
                    apiList.size(), MAX_API, MAX_API,
                    apiList.subList(MAX_API, apiList.size()).stream()
                            .map(Candidate::name).toList());
            apiList = new ArrayList<>(apiList.subList(0, MAX_API));
        }
        candidates.addAll(apiList);

        // ② scope 过滤（§11.4：外部工具永不给前台）
        List<Candidate> visible = new ArrayList<>();
        for (Candidate c : candidates) {
            if (scopeAllowed(c.scope(), caller.invokerType())) {
                visible.add(c);
            } else {
                log.debug("AI 工具 [{}] 的 scope={} 不适用于调用者 {}，本轮不装配",
                        c.name(), c.scope(), caller.invokerType());
            }
        }

        // ③ priority 升序（同名按工具名兜底，保证顺序稳定、可复现）
        visible.sort(Comparator.comparingInt(Candidate::priority).thenComparing(Candidate::name));

        // ④ 单轮上限 12：被截断的必须记 WARN（§11.4：不要静默截断）
        List<Candidate> selected = visible;
        if (visible.size() > MAX_PER_ROUND) {
            selected = visible.subList(0, MAX_PER_ROUND);
            log.warn("AI 工具单轮装配被截断：可用 {} 个，仅装配前 {} 个（按 priority 升序），"
                            + "被截断的工具：{}。若这些工具本该被用上，请调低其 priority 或调高上限",
                    visible.size(), MAX_PER_ROUND,
                    visible.subList(MAX_PER_ROUND, visible.size()).stream()
                            .map(Candidate::name).toList());
        }

        // ⑤ 跳过熔断中的工具（§11.5：熔断期间该工具不装配）
        List<ToolCallback> callbacks = new ArrayList<>(selected.size());
        for (Candidate c : selected) {
            String toolName = c.name();
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
                // ⚠️ 「改过描述但还没点确认」也要挡住（§11.2 的「自动禁用」）：
                //   上面那个 isNotNull 只挡「从没确认过」，挡不住「确认过、后来又改了描述」——
                //   那时 confirmed_at 还是旧值，工具照样装配 ⇒ 改描述这个动作就等于没审批。
                //   判据与后台列表的「待确认」一致：最后修改时间晚于最后确认时间。
                if (cfg.getConfirmedAt() != null && cfg.getUpdatedAt() != null
                        && cfg.getUpdatedAt().isAfter(cfg.getConfirmedAt())) {
                    log.info("API 工具 [{}] 的描述/参数已变更且未确认，本轮不装配（§11.2 自动禁用）",
                            cfg.getToolName());
                    continue;
                }
                result.add(new Candidate(
                        apiCallback(cfg, caller),
                        cfg.getScope(),
                        cfg.getPriority() == null ? DEFAULT_PRIORITY : cfg.getPriority()));
            }
        } catch (Exception e) {
            // ⚠️ 查表失败不能让整轮对话挂掉：退化成"只有内置工具"
            log.error("读取 AI 工具配置失败，本轮只装配内置工具：{}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 内置工具在表里的配置（工具名 → 配置行），一次查完。
     * <p>
     * ⚠️ <b>查表失败就当没配</b>（而不是"查不到就不装配"）：查表失败不该让所有内置工具集体消失。
     */
    private Map<String, AiToolConfig> builtinConfig() {
        try {
            Map<String, AiToolConfig> map = new HashMap<>();
            for (AiToolConfig cfg : configMapper.selectList(new LambdaQueryWrapper<AiToolConfig>()
                    .eq(AiToolConfig::getToolType, AiToolConfig.TYPE_BUILTIN))) {
                map.put(cfg.getToolName(), cfg);
            }
            return map;
        } catch (Exception e) {
            log.error("读取内置工具配置失败，本轮按全部启用处理：{}", e.getMessage(), e);
            return Map.of();
        }
    }

    /** API 工具从数据库构建定义，执行交给 {@link ApiToolInvoker}。 */
    private ToolCallback apiCallback(AiToolConfig cfg, AiToolCaller caller) {
        String schema = cfg.getParamSchema();
        ToolDefinition definition = ToolDefinition.builder()
                .name(cfg.getToolName())
                .description(cfg.getDescription())
                .inputSchema(schema == null || schema.isBlank() ? EMPTY_SCHEMA : schema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String argumentsJson) {
                return apiInvoker.invoke(cfg, argumentsJson,
                        caller.conversationId(), caller.invokerType(), caller.invokerId());
            }
        };
    }

    private record Candidate(ToolCallback callback, String scope, int priority) {
        String name() {
            return callback.getToolDefinition().name();
        }
    }
}
