package com.unicorn.studio.AIWorker.tool.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内置工具的共性支撑（参数解析、体积控制、返回包装）。
 * <p>
 * <b>为什么是静态工具类而不是基类</b>：工具本身已经要实现 {@code AiTool} 接口才能被 Spring 扫到，
 * 再套一层抽象基类就是"接口 + 继承"两层，新人写第一个工具时得同时理解两套写法。
 * 这里的方法都不涉及依赖注入，静态最省事（项目里 {@code WorkTags} 是同一套路）。
 */

public final class AiToolSupport {

    private static final Logger log = LoggerFactory.getLogger(AiToolSupport.class);
    private AiToolSupport() {
    }

    /**
     * 解析模型给的参数 JSON。
     * <p>
     * <b>为什么不用参数 POJO</b>：模型的入参是动态的（这次给 category、下次给 limit），
     * 定义 POJO 反而束手束脚；而且 POJO 会让"模型传错类型"直接抛异常打断整轮对话。
     *
     * @param argumentsJson 模型给的参数（可能为空、可能不是合法 JSON）
     * @return 参数 Map；解析不出来时返回空 Map（调用方按"没传参数"处理，不报错）
     */
    public static Map<String, String> parseArgs(String argumentsJson, ObjectMapper mapper) {
        Map<String, String> args = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return args;
        }
        try {
            JsonNode node = mapper.readTree(argumentsJson);
            if (node == null || !node.isObject()) {
                return args;
            }
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                // ⚠️ 只取标量值：对象/数组塞进参数里没有意义（模型偶尔会传结构化的东西）
                args.put(e.getKey(), v.isValueNode() ? v.asText() : v.toString());
            });
        } catch (Exception ignored) {
            // 参数不是合法 JSON ⇒ 当作"没传参数"，不打断整轮对话
        }
        return args;
    }

    /**
     * 取整数参数；取不到就用默认值。
     * <p>
     * ⚠️ 不用 {@code Integer.parseInt} 直接抛：模型偶尔会传 {@code "limit": "5"}（字符串），
     * 抛异常会打断整轮对话。这里能转就转，转不了当没传。
     */
    public static int intArg(Map<String, String> args, String key, int defaultValue, int maxValue) {
        String raw = args.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
        if (value <= 0) {
            return defaultValue;
        }
        // ⚠️ 上限必须钳住：§4 P0-2「limit 上限 10 是硬要求」——
        // 列表越大 token 越贵、首字越慢，且模型对长列表的引用精度会下降
        return Math.min(value, maxValue);
    }

    /**
     * 取字符串参数；空值统一成 null（调用方用 null 表示"不过滤"）。
     */
    public static String stringArg(Map<String, String> args, String key) {
        String raw = args.get(key);
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return v.isEmpty() ? null : v;
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
    public static String ok(ObjectMapper mapper, Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("data", data);
        return write(mapper, body, "{\"source\":\"tool\",\"error\":\"internal\"}");
    }

    /**
     * 包失败结果：{@code {"source":"tool","error":"...","message":"...","retryable":bool,"field":...}}。
     * <p>
     * ⚠️ <b>不抛异常</b>：工具是在 SSE 流中途被框架调用的，异常会冒到 {@code DefaultChatClient} 内部，
     * 整条 SSE 直接崩掉（用户看到"AI 卡住然后报错"）。失败必须伪装成"一次正常的返回"。
     */
    public static String error(ObjectMapper mapper, String code, String message, String field, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", "tool");
        body.put("error", code);
        body.put("message", message);
        body.put("retryable", retryable);
        if (field != null) {
            body.put("field", field);
        }
        return write(mapper, body, "{\"source\":\"tool\",\"error\":\"internal\",\"retryable\":false}");
    }

    private static String write(ObjectMapper mapper, Map<String, Object> body, String fallback) {
        try {
            // 尽量返回规范的 JSON字符串，为了让模型更容易解析
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            // 最后一道保险：连错误 JSON 都序列化不出来时返回字面量，保证 SSE 不崩
            log.error("工具结果序列化失败，已降级为字面量：{}", e.getMessage());
            return fallback;
        }
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
