package com.unicorn.studio.AIWorker.tool.impl;

import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 示例工具：返回服务器当前时间。
 * <p>
 * 作用有二：① 演示「新增工具」的标准写法（实现接口 + {@code @Component} 即被自动注册）；
 * ② 作为工具链路的端到端冒烟用例 —— 开启 {@code app.ai.tools.enabled=true} 后，
 * 问「现在几点」即可验证 Function Calling 全链路。
 * <p>
 * 默认不启用（{@link #enabled()} 返回 false），避免在「基础问答」阶段干扰模型；
 * 若要启用，把 {@code enabled()} 改为 true 或统一由 {@code app.ai.tools.enabled} 控制。
 */
// @Component  // 预留：取消注释即注册为可用工具
public class CurrentTimeTool implements AiTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public AiToolDefinition definition() {
        return AiToolDefinition.noArgs("current_time", "获取服务器当前日期时间，当用户询问「现在几点/今天几号」时调用。");
    }

    @Override
    public String invoke(String argumentsJson) {
        return "{\"datetime\":\"" + LocalDateTime.now().format(FORMATTER) + "\"}";
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
