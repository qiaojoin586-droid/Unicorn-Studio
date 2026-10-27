package com.unicorn.studio.AIWorker.tool;

import java.util.List;
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

    /** 参数类型（文档 §11.1 P2：工具实际需要数字、布尔与枚举，不能只有 string）。 */
    public enum ParamType {
        STRING("string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
        /** 枚举在 JSON Schema 里类型仍是 string，约束靠 {@code enum:[...]} 表达。 */
        ENUM("string");

        private final String jsonType;

        ParamType(String jsonType) {
            this.jsonType = jsonType;
        }

        public String jsonType() {
            return jsonType;
        }
    }

    /**
     * 单个参数的声明。
     *
     * @param name        参数名（模型的入参名）
     * @param description 用途描述（模型据此决定传什么，务必写清）
     * @param type        类型；{@link ParamType#ENUM} 时 {@code enumValues} 必须非空
     * @param required    是否必填（写进 Schema 的 {@code required} 数组）
     * @param enumValues  枚举可选值（逗号分隔，仅 ENUM 用）
     */
    public record Param(String name, String description, ParamType type,
                         boolean required, String enumValues) {

        /** 必填参数。 */
        public static Param of(String name, ParamType type, String description) {
            return new Param(name, description, type, true, null);
        }

        /** 选填参数。 */
        public static Param optional(String name, ParamType type, String description) {
            return new Param(name, description, type, false, null);
        }

        /** 枚举参数（逗号分隔可选值，如 {@code news,learning,site}）。 */
        public static Param enumeration(String name, String description, String enumValues) {
            return new Param(name, description, ParamType.ENUM, false, enumValues);
        }
    }

    /**
     * 按参数声明列表构建 Schema（文档 §11.1 P2）。
     * <p>
     * 手写 JSON 而非引 Jackson：Schema 是启动期生成的常量字符串（几十字节），
     * 为它引入序列化依赖不划算；且 {@code enum} 数组要嵌进属性对象内部，
     * 用对象序列化反而要额外拼装。
     * <p>
     * ⚠️ {@code required} 是<b>数组</b>而非"每个属性一个布尔"——这是 JSON Schema
     * 与直觉最容易差的一处，写成对象会被多数模型侧实现忽略。
     */
    public static AiToolDefinition withParams(String name, String description, List<Param> params) {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (Param p : params) {
            if (props.length() > 0) {
                props.append(',');
            }
            props.append('"').append(escape(p.name())).append("\":{\"type\":\"")
                    .append(p.type().jsonType()).append("\",\"description\":\"")
                    .append(escape(p.description())).append('"');
            if (p.type() == ParamType.ENUM && p.enumValues() != null && !p.enumValues().isBlank()) {
                props.append(",\"enum\":[");
                String[] values = p.enumValues().split("[,，]");
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) {
                        props.append(',');
                    }
                    props.append('"').append(escape(values[i].trim())).append('"');
                }
                props.append(']');
            }
            props.append('}');
            if (p.required()) {
                if (required.length() > 0) {
                    required.append(',');
                }
                required.append('"').append(escape(p.name())).append('"');
            }
        }
        String schema = "{\"type\":\"object\",\"properties\":{" + props
                + "},\"required\":[" + required + "]}";
        return new AiToolDefinition(name, description, schema);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
