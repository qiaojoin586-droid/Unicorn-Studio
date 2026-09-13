package com.unicorn.studio.AIWorker.tool;

import java.util.Map;

/**
 * 工具定义（与 Spring AI 解耦的传输对象）。
 * <p>
 * 之所以不直接复用 Spring AI 的 {@code ToolDefinition}，是为了让「工具实现」不依赖具体 AI 框架：
 * 将来更换模型供应商（OpenAI / 通义 / 自研网关）或升级 Spring AI 大版本，工具层代码零改动，
 * 仅在 {@code AiToolRegistry} 的装配处做一次适配转换。
 *
 * @param name        工具名称（模型调用时引用，全局唯一）
 * @param description 用途描述（模型据此判断是否调用，需具体）
 * @param inputSchema 参数 JSON Schema（对象形式，如 {@code {"type":"object","properties":{...}}}
 *                    的字符串化结果）
 */
public record AiToolDefinition(String name, String description, String inputSchema) {

    /** 无参工具的快捷构造。 */
    public static AiToolDefinition noArgs(String name, String description) {
        return new AiToolDefinition(name, description,
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}");
    }

    /** 由参数描述构建 Schema（均为 string 类型参数的简化场景）。 */
    public static AiToolDefinition withStringParams(String name, String description, Map<String, String> params) {
        StringBuilder props = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (props.length() > 0) {
                props.append(',');
            }
            props.append('"').append(escape(e.getKey())).append("\":{\"type\":\"string\",\"description\":\"")
                    .append(escape(e.getValue())).append("\"}");
        }
        String schema = "{\"type\":\"object\",\"properties\":{" + props + "},\"required\":[]}";
        return new AiToolDefinition(name, description, schema);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
