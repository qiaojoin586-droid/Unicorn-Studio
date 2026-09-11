package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.SiteSettings;
import com.unicorn.studio.service.SensitiveWordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点设置（实施方案 §3.6 / §5.x）：
 * - 后台：按 group 分组读写（basic/contact/social/seo/notify/content/about）；
 * - 前台：GET /api/site/settings 缓存 10 分钟（排除敏感组）。
 * - V12 起 about 组由「关于我们」专用页面读写（AboutPageService），走同一存储与写入通道。
 * <p>
 * Redis 缓存（V15 §5.2 三视图）：{@link #listGroup} 走 {@code cache:site:group:{group}}（TTL 30 min），
 * {@link #updateAll} 事务提交后按模式失效 {@code cache:site:group:*}。
 * {@link #getValue} 与 {@link #publicSettings} 不加 Redis 缓存：前者是运维运行时键（R5 写后读风险）、
 * 后者已有 JVM 缓存且前台未接入。
 */
@Service
public class SiteSettingsService {

    /** 前台公开组（不含敏感配置） */
    private static final List<String> PUBLIC_GROUPS =
            List.of("basic", "contact", "social", "seo", "content");

    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000;

    /** 组级快照缓存 key 前缀（§5.1） */
    private static final String GROUP_CACHE_PREFIX = "cache:site:group:";
    private static final Duration SITE_TTL = Duration.ofMinutes(30);
    private static final JavaType MAP_STRING_STRING =
            TypeFactory.defaultInstance().constructMapType(LinkedHashMap.class, String.class, String.class);

    private final SiteSettingsMapper settingsMapper;
    private final SensitiveWordService sensitiveWordService;
    private final CacheHelper cache;

    // 前台缓存：{value, cachedAt}
    private volatile Map<String, Object> publicCache;
    private volatile long publicCacheAt;

    public SiteSettingsService(SiteSettingsMapper settingsMapper,
                               SensitiveWordService sensitiveWordService,
                               CacheHelper cache) {
        this.settingsMapper = settingsMapper;
        this.sensitiveWordService = sensitiveWordService;
        this.cache = cache;
    }

    /** 后台：按 group 分组 KV。 */
    public Map<String, Map<String, String>> listGrouped() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        for (SiteSettings s : settingsMapper.findAllByOrderByGroupNameAscSettingKeyAsc()) {
            groups.computeIfAbsent(s.getGroupName(), k -> new LinkedHashMap<>())
                    .put(s.getSettingKey(), s.getSettingValue());
        }
        return groups;
    }

    /** 后台：读取单个分组的 KV（V12 起供「关于我们」等专用页面配置读取）；Redis 缓存 30 min。 */
    public Map<String, String> listGroup(String groupName) {
        String cacheKey = GROUP_CACHE_PREFIX + groupName;
        return cache.<Map<String, String>>get(cacheKey, MAP_STRING_STRING).orElseGet(() -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (SiteSettings s : settingsMapper.findByGroupName(groupName)) {
                values.put(s.getSettingKey(), s.getSettingValue());
            }
            cache.set(cacheKey, values, SITE_TTL);
            return values;
        });
    }

    /** 后台批量更新。 */
    @Transactional
    public void updateAll(List<KeyValueRequest> items) {
        if (items == null) {
            return;
        }
        boolean sensitiveChanged = false;
        for (KeyValueRequest item : items) {
            if (item.key() == null || item.key().isBlank()) {
                throw BusinessException.badRequest("设置项 key 不能为空");
            }
            if (SensitiveWordService.KEY.equals(item.key())) {
                sensitiveChanged = true;
            }
            Optional<SiteSettings> existing = settingsMapper.findBySettingKey(item.key());
            SiteSettings setting = existing.orElseGet(() -> SiteSettings.builder()
                    .groupName(item.group())
                    .settingKey(item.key())
                    .build());
            setting.setSettingValue(item.value());
            if (existing.isPresent()) {
                settingsMapper.updateById(setting);
            } else {
                settingsMapper.insert(setting);
            }
        }
        invalidatePublicCache();
        // 组级快照 Redis 缓存：事务提交后失效（§5.3 陷阱 1，避免「清完缓存又回滚/回填旧值」竞态）
        cache.evictByPatternAfterCommit(GROUP_CACHE_PREFIX + "*");
        // 页面级 VO 缓存（A2/A3）：页脚/关于我们正文由本通道写入，同步失效
        cache.evictAfterCommit(CacheKeys.FOOTER_PAGE, CacheKeys.ABOUT_PAGE);
        // 敏感词库字段级缓存：写后失效，下次评论/留言校验回源（§2.5 P-1）
        if (sensitiveChanged) {
            sensitiveWordService.invalidate();
        }
    }

    /** 按 key 读单个值。 */
    public String getValue(String key) {
        return settingsMapper.findBySettingKey(key)
                .map(SiteSettings::getSettingValue)
                .orElse(null);
    }

    /** 前台公开设置（缓存 10 分钟）。 */
    public Map<String, Map<String, String>> publicSettings() {
        long now = System.currentTimeMillis();
        Map<String, Object> cache = publicCache;
        if (cache != null && now - publicCacheAt < CACHE_TTL_MILLIS) {
            return cast(cache);
        }
        synchronized (this) {
            if (publicCache != null && System.currentTimeMillis() - publicCacheAt < CACHE_TTL_MILLIS) {
                return cast(publicCache);
            }
            Map<String, Map<String, String>> groups = new LinkedHashMap<>();
            for (SiteSettings s : settingsMapper.findAllByOrderByGroupNameAscSettingKeyAsc()) {
                if (!PUBLIC_GROUPS.contains(s.getGroupName())) {
                    continue;
                }
                groups.computeIfAbsent(s.getGroupName(), k -> new LinkedHashMap<>())
                        .put(s.getSettingKey(), s.getSettingValue());
            }
            publicCache = new ConcurrentHashMap<>(groups);
            publicCacheAt = System.currentTimeMillis();
            return groups;
        }
    }

    public void invalidatePublicCache() {
        publicCache = null;
        publicCacheAt = 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> cast(Map<String, Object> cache) {
        return (Map<String, Map<String, String>>) (Map<?, ?>) cache;
    }

    public record KeyValueRequest(String group, String key, String value) {
    }
}
