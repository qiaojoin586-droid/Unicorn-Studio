package com.unicorn.studio.AIWorker.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 工具层的装配中心。
 * <p>
 * Spring 会注入所有 {@link AiTool} 实现（{@code List<AiTool>}），本类负责：
 * <ol>
 *   <li>按名称去重（重名后者覆盖并告警，避免模型侧歧义）；</li>
 *   <li>过滤 {@link AiTool#enabled()} 为 false 的候选；</li>
 *   <li>把与框架无关的 {@link AiToolDefinition} 适配为 Spring AI 的 {@link ToolCallback}。</li>
 * </ol>
 * 当前阶段 {@code app.ai.tools.enabled=false}（默认），即「只留扩展位、不装配工具」，
 * 符合「现阶段先实现最基础的问答功能」；后续要开工具能力，改配置即可，无需改代码。
 */
@Component
public class AiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistry.class);

    private final boolean toolsEnabled;
    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public AiToolRegistry(List<AiTool> candidates,
                          @Value("${app.ai.tools.enabled:false}") boolean toolsEnabled) {
        this.toolsEnabled = toolsEnabled;
        if (candidates != null) {
            for (AiTool tool : candidates) {
                if (!tool.enabled()) {
                    log.info("AI 工具 [{}] 已注册但当前禁用（enabled=false）", name(tool));
                    continue;
                }
                String name = name(tool);
                if (tools.containsKey(name)) {
                    log.warn("AI 工具名称重复 [{}]，后者覆盖前者，请检查实现类命名", name);
                }
                tools.put(name, tool);
            }
        }
        log.info("AI 工具层初始化完成：候选 {} 个，可用 {} 个，装配开关 app.ai.tools.enabled={}",
                candidates == null ? 0 : candidates.size(), tools.size(), toolsEnabled);
    }

    /** 已注册的工具名列表（供后台/诊断查看）。 */
    public List<String> registeredNames() {
        return new ArrayList<>(tools.keySet());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /**
     * 装配给模型的工具回调。
     * <p>
     * 返回空数组时，Spring AI 的 ChatClient 不会发送 tools 字段，
     * 因此「零工具」状态下的对话行为与纯问答完全一致（符合当前阶段目标）。
     */
    public ToolCallback[] callbacks() {
        if (!toolsEnabled || tools.isEmpty()) {
            return new ToolCallback[0];
        }
        List<ToolCallback> callbacks = new ArrayList<>(tools.size());
        for (AiTool tool : tools.values()) {
            callbacks.add(adapt(tool));
        }
        return callbacks.toArray(new ToolCallback[0]);
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
