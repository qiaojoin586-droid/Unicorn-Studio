package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.config.AiApiUrlValidator;
import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.config.AiRuntimeConfig;
import com.unicorn.studio.AIWorker.dto.AiConfigUpdateRequest;
import com.unicorn.studio.AIWorker.dto.AiTestRequest;
import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.AIWorker.vo.AiConfigVO;
import com.unicorn.studio.AIWorker.vo.AiTestResultVO;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.crypto.CryptoService;
import com.unicorn.studio.model.SiteSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 配置管理服务（后台）—— 加密存储 + 连通性测试。
 * <p>
 * <b>安全要求落地</b>：
 * <ul>
 *   <li>API-key <b>加密后落库</b>（{@link CryptoService#encrypt}），数据库中不存在明文 key；</li>
 *   <li>后台读取一律走 {@link CryptoService#mask} 脱敏，完整 key 不出后端；</li>
 *   <li>保存时若提交的是掩码串/空值，视为「未修改」，保留库中原密文 —— 避免误清空。</li>
 * </ul>
 * <b>热生效</b>：保存后事务提交时失效 {@link AiConfigProvider#CACHE_KEY}，
 * 下一轮对话立刻使用新配置，无需重启（与项目既有 afterCommit 失效约定一致）。
 */
@Service
public class AiConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiConfigService.class);

    /** 连通性探测的固定问句：短、稳定、成本低 */
    private static final String PROBE_MESSAGE = "请只回复两个字：连通";

    /**
     * 探测专用 maxToken 预算。
     * <p>
     * 为什么不能沿用配置里的值？{@code maxTokens} 是「正式对话」的参数，可能被管理员调得很小；
     * 而当前主流的国产模型（GLM-4.5-Air、DeepSeek-R1、QwQ…）都是<b>推理模型</b>，
     * 会先消耗 token 输出 {@code reasoning_content}，预算过小时 {@code content} 直接为空、
     * {@code finish_reason} 为 {@code length} —— 探测就会误判成「配置不可用」。
     * 给足 512 既能容纳思考过程，又足够便宜。
     */
    private static final int PROBE_MAX_TOKENS = 512;

    private final AiConfigMapper configMapper;
    private final AiConfigProvider configProvider;
    private final CryptoService crypto;
    private final AiChatService chatService;
    private final CacheHelper cache;
    private final AiApiUrlValidator apiUrlValidator;

    public AiConfigService(AiConfigMapper configMapper,
                           AiConfigProvider configProvider,
                           CryptoService crypto,
                           AiChatService chatService,
                           CacheHelper cache,
                           AiApiUrlValidator apiUrlValidator) {
        this.configMapper = configMapper;
        this.configProvider = configProvider;
        this.crypto = crypto;
        this.chatService = chatService;
        this.cache = cache;
        this.apiUrlValidator = apiUrlValidator;
    }

    /** 读取配置（脱敏回显）。 */
    public AiConfigVO current() {
        Map<String, String> raw = configProvider.rawGroup();
        String storedKey = raw.get(AiConfigProvider.KEY_API_KEY);
        boolean keySet = storedKey != null && !storedKey.isBlank();
        AiRuntimeConfig runtime = configProvider.runtimeConfig();
        return AiConfigVO.of(runtime,
                keySet ? crypto.mask(storedKey) : "",
                keySet,
                !crypto.isPlainFallback());
    }

    /**
     * 保存配置。
     * <p>
     * 只写“有值”的字段（null 表示该字段本次未提交，保持原值），
     * 其中 apiKey 额外做「掩码串视为未修改」判断。
     */
    @Transactional
    public void save(AiConfigUpdateRequest form) {
        if (form == null) {
            return;
        }
        Map<String, String> values = new LinkedHashMap<>();
        // S-3：保存路径同样必须校验 —— 否则可以「先存一个内网/攻击者地址，再触发对话」绕开探测路径的检查
        apiUrlValidator.validate(form.apiUrl());
        if (!form.apiKeyUnchanged()) {
            String plainKey = form.apiKey().trim();
            if (!plainKey.isEmpty()) {
                // 关键安全点：落库前加密（CryptoService.encrypt 幂等，重复保存不会二次加密）
                values.put(AiConfigProvider.KEY_API_KEY, crypto.encrypt(plainKey));
            }
        } else {
            log.debug("AI 配置保存：apiKey 判定为「未修改」，保留库中原密文");
        }
        putIfPresent(values, AiConfigProvider.KEY_API_URL, form.apiUrl());
        putIfPresent(values, AiConfigProvider.KEY_MODEL, form.model());
        putIfPresent(values, AiConfigProvider.KEY_SYSTEM_PROMPT, form.systemPrompt());
        if (form.maxTokens() != null) {
            values.put(AiConfigProvider.KEY_MAX_TOKENS, String.valueOf(form.maxTokens()));
        }
        if (form.temperature() != null) {
            values.put(AiConfigProvider.KEY_TEMPERATURE, String.valueOf(form.temperature()));
        }
        if (form.enabled() != null) {
            values.put(AiConfigProvider.KEY_ENABLED, String.valueOf(form.enabled()));
        }

        // 单条语句批量 upsert（B-11）：原先循环内逐条 upsert = 每个字段 2 条语句
        // （findBySettingKey + updateById/insert），7 个字段就是 14 条。
        // 与 SiteSettingsService.updateAll 同语义、同实现（setting_key 上有唯一约束）。
        if (!values.isEmpty()) {
            configMapper.batchUpsert(values.entrySet().stream()
                    .map(e -> SiteSettings.builder()
                            .groupName(AiConfigProvider.GROUP)
                            .settingKey(e.getKey())
                            .settingValue(e.getValue())
                            .build())
                    .toList());
        }
        // 配置改了：事务提交后统一失效快照缓存（避免「清完缓存又回滚」竞态）
        cache.evictAfterCommit(AiConfigProvider.CACHE_KEY);
        log.info("AI 配置已更新，字段：{}", values.keySet());
    }

    /**
     * 测试连通性。
     * <p>
     * 优先用请求中携带的表单值（用户可「先测再存」）；未填写则回退库中已保存配置。
     * 任何异常都归一为 {@link AiTestResultVO} 失败结果返回，不打挂请求。
     */
    public AiTestResultVO test(AiTestRequest form) {
        AiRuntimeConfig probe = resolveProbeConfig(form);
        if (probe == null) {
            return AiTestResultVO.fail("-", "-", 0, "请先填写 API Key、API URL 与模型名，或先保存一份完整配置");
        }
        if (probe.apiKey() == null || probe.apiKey().isBlank()) {
            return AiTestResultVO.fail(probe.model(), probe.normalizedBaseUrl(), 0,
                    "未获取到可用的 API Key，请填写后重试");
        }
        if (probe.apiUrl() == null || probe.apiUrl().isBlank()) {
            return AiTestResultVO.fail(probe.model(), "-", 0, "请填写 API URL");
        }
        if (probe.model() == null || probe.model().isBlank()) {
            return AiTestResultVO.fail("-", probe.normalizedBaseUrl(), 0, "请填写模型名");
        }
        // S-3：SSRF 防护。探测会把【库中明文 apiKey】作为 Bearer 头发往该地址，
        // 因此地址必须受限于白名单且不得指向内网。按本方法契约返回失败结果而非抛异常。
        try {
            apiUrlValidator.validate(probe.apiUrl());
        } catch (BusinessException e) {
            log.warn("AI 连通性测试被拒绝：url={}, 原因={}", probe.apiUrl(), e.getMessage());
            return AiTestResultVO.fail(probe.model(), probe.normalizedBaseUrl(), 0, e.getMessage());
        }

        long start = System.currentTimeMillis();
        try {
            String reply = chatService.callOnce(probe, PROBE_MESSAGE);
            long latency = System.currentTimeMillis() - start;
            if (reply == null || reply.isBlank()) {
                // 走到这里说明「HTTP 通了、鉴权过了、模型也响应了」，只是没拿到正文 —— 属于推理模型
                // 思考过程占满预算的典型表现，语义上应算「连通成功」，但需明确提示以免误以为完全正常。
                log.warn("AI 连通性测试：接口可达但正文为空（疑似推理模型耗尽 token 预算），model={}, url={}",
                        probe.model(), probe.normalizedBaseUrl());
                return AiTestResultVO.ok(probe.model(), probe.normalizedBaseUrl(), latency,
                        "接口连通、鉴权通过（模型未返回正文，常见于推理模型思考过程占用 token，可正常使用）");
            }
            log.info("AI 连通性测试成功：model={}, url={}, latency={}ms",
                    probe.model(), probe.normalizedBaseUrl(), latency);
            return AiTestResultVO.ok(probe.model(), probe.normalizedBaseUrl(), latency, reply);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String reason = humanizeError(e);
            log.warn("AI 连通性测试失败：model={}, url={}, 原因={}",
                    probe.model(), probe.normalizedBaseUrl(), e.getMessage());
            return AiTestResultVO.fail(probe.model(), probe.normalizedBaseUrl(), latency, reason);
        }
    }

    /** 组装探测用配置：表单优先，缺失字段逐个回退到库中值。 */
    private AiRuntimeConfig resolveProbeConfig(AiTestRequest form) {
        AiRuntimeConfig saved = configProvider.runtimeConfig();
        if (form == null || form.allBlank()) {
            return saved;
        }
        String apiKey = form.apiKeyUnchanged() ? saved.apiKey() : form.apiKey().trim();
        String apiUrl = blankToNull(form.apiUrl()) == null ? saved.apiUrl() : form.apiUrl().trim();
        String model = blankToNull(form.model()) == null ? saved.model() : form.model().trim();
        // maxTokens 固定用探测预算：正式对话的参数可能被调得很小，不足以让推理模型吐出正文
        return new AiRuntimeConfig(apiKey, apiUrl, model,
                saved.systemPrompt(), PROBE_MAX_TOKENS, saved.temperature(), true);
    }

    /** 把底层异常翻译成运维能直接定位的中文原因。 */
    private String humanizeError(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key")
                || lower.contains("authentication")) {
            return "鉴权失败（401）：API Key 无效或已过期，请检查后重新填写";
        }
        if (lower.contains("404") || lower.contains("not found")) {
            return "接口不存在（404）：请检查 API URL 是否为兼容 OpenAI 协议的 base 地址"
                    + "（智谱填 https://open.bigmodel.cn/api/paas/v4，OpenAI 官方填 https://api.openai.com/v1）";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "触发供应商限流（429）：请稍后重试或检查账户额度";
        }
        if (lower.contains("model") && (lower.contains("not exist") || lower.contains("unknown")
                || lower.contains("invalid"))) {
            return "模型名不存在：请核对模型名是否与供应商文档一致";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时：请检查 API URL 是否可达、网络是否存在代理限制";
        }
        if (lower.contains("connection") || lower.contains("connect") || lower.contains("unknownhost")
                || lower.contains("nodename")) {
            return "无法建立连接：请检查 API URL 域名是否正确、服务器能否访问外网";
        }
        return "调用失败：" + (msg.isBlank() ? e.getClass().getSimpleName() : msg);
    }

    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
