package com.unicorn.studio.AIWorker.config;

import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.crypto.CryptoService;
import com.unicorn.studio.model.SiteSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 配置提供者 —— 「后台可维护 + 热生效」的关键。
 * <p>
 * <b>为什么不用 {@code @Value}？</b>历史实现把 api-key/url/model 放在 {@code application.yml}，
 * 改一次要改配置+重启。现在统一落到 {@code site_settings} 的 {@code ai} 组，后台改完即时生效。
 * <p>
 * <b>读取策略（Cache-Aside）</b>：
 * <ol>
 *   <li>先查 Redis {@code cache:ai:config}（TTL 5 min，短 TTL 兜底多实例场景）；</li>
 *   <li>未命中回源 MySQL → 解密 api-key → 组装 {@link AiRuntimeConfig} → 写缓存；</li>
 *   <li>后台保存时 {@code evictAfterCommit} 失效本键 ⇒ 下一轮对话立刻用新配置（无需重启）。</li>
 * </ol>
 * <b>降级约定</b>：Redis 不可用时 {@link CacheHelper} 静默降级为直读 MySQL，本类逻辑不受影响。
 */
@Component
public class AiConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(AiConfigProvider.class);

    /** 缓存键（§5.1 键命名规范：业务域前缀） */
    public static final String CACHE_KEY = "cache:ai:config";

    /** ai 配置组名 */
    public static final String GROUP = "ai";

    // ---- 配置项 key（与 site_settings 中 ai 组的 setting_key 一一对应）----
    public static final String KEY_API_KEY = "ai.api_key";
    public static final String KEY_API_URL = "ai.api_url";
    public static final String KEY_MODEL = "ai.model";
    public static final String KEY_SYSTEM_PROMPT = "ai.system_prompt";
    public static final String KEY_MAX_TOKENS = "ai.max_tokens";
    public static final String KEY_TEMPERATURE = "ai.temperature";
    public static final String KEY_ENABLED = "ai.enabled";

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final AiConfigMapper configMapper;
    private final CryptoService crypto;
    private final CacheHelper cache;

    public AiConfigProvider(AiConfigMapper configMapper, CryptoService crypto, CacheHelper cache) {
        this.configMapper = configMapper;
        this.crypto = crypto;
        this.cache = cache;
    }

    /** 取运行时配置（已解密），供对话与连通性测试使用。 */
    public AiRuntimeConfig runtimeConfig() {
        return cache.get(CACHE_KEY, AiRuntimeConfig.class).orElseGet(() -> {
            AiRuntimeConfig config = loadFromDb();
            cache.set(CACHE_KEY, config, CACHE_TTL);
            return config;
        });
    }

    /**
     * 读取「写侧视图」——api-key 保持密文原样，供后台编辑页回显（配 {@code CryptoService.mask} 脱敏）。
     * 不走缓存：管理端读必须拿到最新值。
     */
    public Map<String, String> rawGroup() {
        Map<String, String> values = new LinkedHashMap<>();
        for (SiteSettings s : configMapper.findByGroupName(GROUP)) {
            values.put(s.getSettingKey(), s.getSettingValue());
        }
        return values;
    }

    /** 配置是否可用（供健康检查/状态展示）。 */
    public boolean usable() {
        return runtimeConfig().usable();
    }

    /** 公开接口只使用头像地址，模型凭据不进入这份缓存或响应。 */
    public AiMessageAvatar messageAvatar() {
        return cache.get(AiMessageAvatar.CACHE_KEY, AiMessageAvatar.class).orElseGet(() -> {
            AiMessageAvatar avatar = AiMessageAvatar.from(rawGroup());
            cache.set(AiMessageAvatar.CACHE_KEY, avatar, CACHE_TTL);
            return avatar;
        });
    }

    private AiRuntimeConfig loadFromDb() {
        Map<String, String> raw = rawGroup();
        String encryptedKey = raw.get(KEY_API_KEY);
        String apiKey = null;
        if (encryptedKey != null && !encryptedKey.isBlank()) {
            try {
                apiKey = crypto.decrypt(encryptedKey);
            } catch (RuntimeException e) {
                // 单条配置解密失败不应打挂整个对话功能：记日志并视为「未配置」
                log.error("AI api-key 解密失败，请检查 APP_CRYPTO_SECRET 是否与写入时一致", e);
            }
        }
        return new AiRuntimeConfig(
                apiKey,
                valueOrDefault(raw.get(KEY_API_URL), "https://open.bigmodel.cn/api/paas/v4"),
                valueOrDefault(raw.get(KEY_MODEL), "GLM-4.5-Air"),
                valueOrDefault(raw.get(KEY_SYSTEM_PROMPT), AiRuntimeConfig.DEFAULT_SYSTEM_PROMPT),
                parseInt(raw.get(KEY_MAX_TOKENS), 1024),
                parseDouble(raw.get(KEY_TEMPERATURE)),
                parseBool(raw.get(KEY_ENABLED), true));
    }

    private static String valueOrDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static Integer parseInt(String v, int def) {
        try {
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double parseDouble(String v) {
        try {
            return v == null || v.isBlank() ? null : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null || v.isBlank()) {
            return def;
        }
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    /** 供缓存预热/诊断使用：把 Optional 语义暴露出去（当前未使用，保留给后续预热任务）。 */
    public Optional<AiRuntimeConfig> cached() {
        return cache.get(CACHE_KEY, AiRuntimeConfig.class);
    }
}
