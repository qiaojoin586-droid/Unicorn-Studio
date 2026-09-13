package com.unicorn.studio.AIWorker.config;

import com.unicorn.studio.common.BusinessException;

import java.util.Locale;

/**
 * AI 运行时配置（内存快照）。
 * <p>
 * 由 {@link AiConfigProvider} 从 {@code site_settings} 的 {@code ai} 组读取并解密后组装。
 * 不可变 record，配合 volatile 快照实现「改配置即时生效、无需重启」。
 * <p>
 * 注意：此处持有的是<b>已解密</b>的明文 apiKey，仅存在于服务端内存，
 * 严禁序列化进任何响应体（后台回显走 {@code CryptoService.mask}）。
 *
 * @param apiKey   明文 API Key（已解密）
 * @param apiUrl    兼容 OpenAI 协议的 base-url（不含 /chat/completions 路径）
 * @param model     模型名
 * @param systemPrompt 系统提示词
 * @param maxTokens 单次回复最大 token
 * @param temperature 采样温度
 * @param enabled   功能总开关
 */
public record AiRuntimeConfig(String apiKey,
                              String apiUrl,
                              String model,
                              String systemPrompt,
                              Integer maxTokens,
                              Double temperature,
                              boolean enabled) {

    /** Spring AI 默认拼接的对话补全路径常量，用于「用户已手填 /v1」时避免路径重复。 */
    private static final String DEFAULT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 默认系统提示词（与历史实现保持一致的「小独角兽」人设）。 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是独角兽工作室的AI助手，名叫小独角兽。你友好、专业，擅长回答关于工作室、技术学习和编程的问题。请用中文回答。";

    /** 是否具备调用真实模型的条件（总开关开启且 key、url、model 齐备）。 */
    public boolean usable() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && apiUrl != null && !apiUrl.isBlank()
                && model != null && !model.isBlank();
    }

    /**
     * 归一到 Spring AI 需要的 base-url。
     * <p>
     * Spring AI 的 {@code OpenAiApi} 会固定在 base-url 之后拼接
     * {@code /v1/chat/completions}（见 {@code OpenAiApi.Builder} 的默认 {@code completionsPath}）。
     * 但国内大多数兼容厂商（智谱、DeepSeek、通义、月之暗面…）的 base-url 已经带版本段
     * （如 {@code https://open.bigmodel.cn/api/paas/v4}），再拼 {@code /v1} 就会变成
     * {@code /v4/v1/chat/completions} 而 404。因此这里做两件事：
     * <ol>
     *   <li>裁掉用户可能误填的 {@code /chat/completions} 或 {@code /v1/chat/completions} 完整路径；</li>
     *   <li>若 base-url 已自带版本段（{@code /vN}、{@code /vN.M}），则把补全路径的 {@code /v1} 前缀
     *       交给 {@link #completionsPath()} 去“接管”，由它保证最终 URL 不重复。</li>
     * </ol>
     */
    public String normalizedBaseUrl() {
        if (apiUrl == null) {
            return "";
        }
        String url = apiUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 完整端点 → base：.../v1/chat/completions / .../chat/completions
        for (String suffix : new String[]{"/v1/chat/completions", "/chat/completions"}) {
            if (url.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                url = url.substring(0, url.length() - suffix.length());
                break;
            }
        }
        return url;
    }

    /**
     * 对话补全路径：默认保持 Spring AI 的 {@code /v1/chat/completions}；
     * 但当 base-url 已经自带版本段（例如智谱的 {@code .../v4}）时，去掉多余的 {@code /v1}，
     * 最终请求为 {@code .../v4/chat/completions} —— 这才是厂商真正提供的端点。
     */
    public String completionsPath() {
        return hasOwnVersionSegment() ? "/chat/completions" : DEFAULT_COMPLETIONS_PATH;
    }

    /**
     * 判断 base-url 的路径部分是否以版本段结尾（{@code /v1}、{@code /v4}、{@code /v1.0} 等）。
     * <p>
     * 对<b>任何厂商</b>（含 OpenAI 官方）语义都一样：base 里已经有版本段，就不再让
     * Spring AI 追加默认的 {@code /v1}，否则必然出现 {@code /v1/v1/chat/completions}。
     * 因此按官方文档填 {@code https://api.openai.com/v1} 也不会重复，而填
     * {@code https://api.openai.com}（无路径）则保持默认、由 Spring AI 补 {@code /v1}，两者都正确。
     */
    private boolean hasOwnVersionSegment() {
        String hostAndPath = stripScheme(normalizedBaseUrl());
        int slash = hostAndPath.indexOf('/');
        if (slash < 0) {
            return false;
        }
        return hostAndPath.substring(slash).matches("(?i).*/v\\d+(\\.\\d+)*");
    }

    private static String stripScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String scheme : new String[]{"https://", "http://"}) {
            if (lower.startsWith(scheme)) {
                return url.substring(scheme.length());
            }
        }
        return url;
    }

    public int maxTokensOrDefault() {
        return maxTokens == null || maxTokens <= 0 ? 1024 : maxTokens;
    }

    public double temperatureOrDefault() {
        return temperature == null ? 0.7 : Math.max(0.0, Math.min(2.0, temperature));
    }

    public String systemPromptOrDefault() {
        return systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    /** 配置不可用时的统一异常（调用方会转成前台可读提示）。 */
    public static BusinessException unavailable() {
        return BusinessException.badRequest("AI 助手尚未完成配置，请联系管理员在后台「AI 配置」中填写并测试连通性");
    }
}
