package com.unicorn.studio.AIWorker.tool.impl;

import com.unicorn.studio.AIWorker.tool.AiTool;
import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 无参工具示例，未注册为 Spring Bean。 */
public class CurrentTimeTool implements AiTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Tool(name = "current_time", description = "获取服务器当前日期时间，当用户询问「现在几点/今天几号」时调用。")
    public Map<String, String> currentTime() {
        return Map.of("datetime", LocalDateTime.now().format(FORMATTER));
    }
}
