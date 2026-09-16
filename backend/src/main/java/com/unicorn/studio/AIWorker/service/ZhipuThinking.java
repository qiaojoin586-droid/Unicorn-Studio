package com.unicorn.studio.AIWorker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「关闭智谱（GLM）深度思考」的策略本体：判断该不该改、以及怎么改请求体。
 * <p>
 * 真正挂到 HTTP 客户端上的两个适配器是 {@link ZhipuThinkingInterceptor}（RestClient）与
 * {@link ZhipuThinkingConnector}（WebClient）—— 本类只回答策略问题，不认识任何 HTTP 接口。
 *
 * <h3>为什么需要它（2026-09-14 故障复盘）</h3>
 * 模型 {@code GLM-4.5-Air} 是混合推理模型，{@code thinking} 默认 {@code enabled}，
 * 而<b>思维链 token 与正文 token 共用 {@code max_tokens} 预算</b>。当 {@code ai.max_tokens} 被设成 512 时：
 * <ul>
 *   <li>「你好」这类无需思考的提问 → 模型直接出正文 → 正常；</li>
 *   <li>「解释一下什么是java泛型」→ 进入思考 → 预算被思维链吃光 → 正文 0 字，
 *       上游返回 <b>HTTP 200 + 空 content</b>（实测：整条 SSE 流正常结束，只有 {@code data:[DONE]}，
 *       前端因此显示「抱歉，这次没有收到有效回复，请重新提问一次」）。</li>
 * </ul>
 *
 * <h3>为什么必须落到 HTTP 层</h3>
 * Spring AI 1.0.9 的 {@code OpenAiChatOptions} / {@code ChatCompletionRequest} 只暴露标准 OpenAI 参数
 * （外加 {@code reasoningEffort}），<b>没有透传自定义字段的入口</b>（已 javap 确认无 {@code extraBody}）；
 * 而智谱要求的是 {@code "thinking":{"type":"disabled"}} 这样的嵌套对象，且
 * {@code reasoning_effort} 在 GLM-4.5 上会被<b>静默忽略</b>（该参数仅 GLM-5.2+ 生效）。
 *
 * <h3>为什么必须按域名收窄</h3>
 * {@code thinking} 是智谱私有参数，OpenAI 官方端点收到未知顶层参数会直接 400
 * （{@code Unrecognized request argument}）。本策略只在智谱域名上生效，
 * 后台把 apiUrl 换成其它厂商时行为自动回到「原样发出」。
 *
 * <h3>失败时的取舍</h3>
 * 改写失败（请求体非 JSON 等）一律<b>原样放行</b> —— 一个「优化参数」绝不该让对话挂掉；
 * 此时退化为修复前的行为（可能空回复），并打一条一次性告警。
 */
public class ZhipuThinking {

    private static final Logger log = LoggerFactory.getLogger(ZhipuThinking.class);

    /**
     * 智谱域名（精确匹配或子域名匹配，与 {@code AiApiUrlValidator} 的白名单口径一致）。
     * {@code open.bigmodel.cn} 为国内站，{@code api.z.ai} 为国际站，同一套 API。
     */
    private static final List<String> DEFAULT_ZHIPU_HOSTS = List.of("bigmodel.cn", "z.ai");

    private static final String THINKING_FIELD = "thinking";
    private static final String TYPE_FIELD = "type";
    private static final String TYPE_DISABLED = "disabled";

    private final List<String> hostSuffixes;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 成功关闭一次后不再重复打日志（否则每轮对话都刷一条） */
    private final AtomicBoolean disabledLogged = new AtomicBoolean(false);
    /** 改写失败只告警一次，避免「每轮对话刷一条 WARN」 */
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public ZhipuThinking() {
        this(DEFAULT_ZHIPU_HOSTS);
    }

    /**
     * 指定生效域名。
     *
     * @param hostSuffixes 域名后缀，如 {@code bigmodel.cn}
     */
    public ZhipuThinking(List<String> hostSuffixes) {
        this.hostSuffixes = List.copyOf(hostSuffixes);
    }

    /** 请求目标是智谱端点吗（域名精确匹配或为其子域名）。 */
    public boolean isZhipuHost(URI uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null) {
            return false;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return hostSuffixes.stream()
                .anyMatch(suffix -> lowerHost.equals(suffix) || lowerHost.endsWith("." + suffix));
    }

    /**
     * 把请求体改写为「关闭思考」的版本。
     *
     * @param body 已经序列化好的请求体字节
     * @param uri  目标地址（非智谱域名直接返回 null）
     * @return 改写后的字节；<b>无需改写、非智谱域名或改写失败时返回 null</b>，调用方按原样发出
     */
    @Nullable
    public byte[] patch(byte[] body, URI uri) {
        if (body == null || body.length == 0 || !isZhipuHost(uri)) {
            return null;
        }
        String host = uri.getHost();
        try {
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            if (json.containsKey(THINKING_FIELD)) {
                // 调用方已显式指定 thinking（例如将来要做「深度思考」开关）——尊重它，不覆盖
                return null;
            }
            json.put(THINKING_FIELD, Map.of(TYPE_FIELD, TYPE_DISABLED));
            byte[] patched = objectMapper.writeValueAsBytes(json);
            if (disabledLogged.compareAndSet(false, true)) {
                log.info("已对智谱端点关闭深度思考（{}={{{}:{}}}）：host={}。"
                                + "此举用于规避「思维链吃光 max_tokens 预算导致正文为空」。",
                        THINKING_FIELD, TYPE_FIELD, TYPE_DISABLED, host);
            }
            return patched;
        } catch (Exception e) {
            if (failureLogged.compareAndSet(false, true)) {
                log.warn("改写请求体以关闭深度思考失败，已按原样发出（可能退化为空回复）：host={}, 原因={}",
                        host, e.getMessage());
            }
            return null;
        }
    }
}
