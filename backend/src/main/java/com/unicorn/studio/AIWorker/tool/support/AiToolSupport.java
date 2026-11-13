package com.unicorn.studio.AIWorker.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 工具参数的默认值、返回结构和审计脱敏。 */
public final class AiToolSupport {

    private AiToolSupport() {
    }

    public static String textArg(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static int boundedLimit(Integer value, int defaultValue, int maxValue) {
        return value == null || value <= 0 ? defaultValue : Math.min(value, maxValue);
    }

    /**
     * HTML 转纯文本（给模型看的内容必须是纯文本）。
     * <p>
     * ⚠️ 用 jsoup 而不是正则：正则去标签会在"属性里带 &gt;"、"正文里有 &lt;" 这类情况出错，
     * 而且会把 {@code <script>} 里的代码也留下。jsoup 是真正的 HTML 解析器，
     * 项目里 {@code HtmlSanitizer} 已经在用（{@code Jsoup.clean}）。
     * <p>
     * ⚠️ 不用 {@code HtmlSanitizer.clean}：那个是"保留安全标签的富文本"（给网页显示用），
     * 给模型的是纯文本，要的是 {@code Jsoup.parse(html).text()}。
     *
     * 数据库查询到的是 HTML 格式的内容，需要转成纯文本给模型。
     */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text().trim();
    }

    /**
     * 截断文本。
     * <p>
     * ⚠️ 调用方必须同时标 {@code truncated}：《工具层建设建议》§7.6「静默截断（模型会以为拿到了全部）」。
     */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * 包成功结果：{@code {"source":"tool","data":{...}}}。
     * <p>
     * ⚠️ {@code source:"tool"} 是给模型的<b>数据边界标记</b>（§11.4 第 3 条）：
     * 让模型知道"这是工具返回的数据"，不是用户说的话、也不是系统指令。
     * ⚠️ 用 {@code LinkedHashMap} 保序：{@code source} 放最前，模型先看到它。
     */
    public static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("data", data);
        return body;
    }

    /**
     * 包失败结果：{@code {"source":"tool","error":"...","message":"...","retryable":bool,"field":...}}。
     * <p>
     * ⚠️ <b>不抛异常</b>：工具是在 SSE 流中途被框架调用的，异常会冒到 {@code DefaultChatClient} 内部，
     * 整条 SSE 直接崩掉（用户看到"AI 卡住然后报错"）。失败必须伪装成"一次正常的返回"。
     */
    public static Map<String, Object> error(String code, String message, String field, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("error", code);
        body.put("message", message);
        body.put("retryable", retryable);
        if (field != null) {
            body.put("field", field);
        }
        return body;
    }

    /**
     * 敏感键名集合 —— 请求头解密与审计参数脱敏共用同一份，
     * 保证"加密过的"和"脱敏的"一定对齐，不会出现「存了明文 token」或「把密文当敏感值脱敏」。
     */
    public static final Set<String> SECRET_KEYS = Set.of(
            "password", "passwd", "pwd", "token", "secret", "apikey", "api_key", "api-key", "authorization");

    /**
     * 参数摘要：脱敏 + 截断（文档 §11.7 硬约束第 1 条：截断 + 脱敏，绝不存全文）。
     * <p>
     * ⚠️ 参数里可能有用户输入的密码/手机号——全文入库会让审计表本身变成二次泄露点，
     * 且体积失控。
     * <p>
     * 用<b>解析成 JSON 再按键脱敏</b>，而不是正则替换：正则处理不了嵌套结构、
     * 也说不清键名是不是恰好出现在某个字符串值里；按键判断更可控。
     *
     * @return 脱敏后的摘要（≤500 字符）；参数为空时返回 null
     */
    public static String digest(String argumentsJson, ObjectMapper mapper) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        String masked;
        try {
            masked = maskNode(mapper.readTree(argumentsJson), mapper).toString();
        } catch (Exception notJson) {
            // 参数本身不是 JSON：原样截断存（比丢弃更有排查价值）
            masked = argumentsJson;
        }
        return masked.length() > 500 ? masked.substring(0, 500) + "…" : masked;
    }

    /** 递归脱敏：命中敏感键名就替换成 ***，否则原样递归。 */
    private static JsonNode maskNode(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            var out = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (SECRET_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    out.put(e.getKey(), "***");
                } else {
                    out.set(e.getKey(), maskNode(e.getValue(), mapper));
                }
            });
            return out;
        }
        if (node.isArray()) {
            var out = mapper.createArrayNode();
            node.forEach(item -> out.add(maskNode(item, mapper)));
            return out;
        }
        return node;
    }
}
