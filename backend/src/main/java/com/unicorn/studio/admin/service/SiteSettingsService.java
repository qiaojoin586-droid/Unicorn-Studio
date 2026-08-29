package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.SiteSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站点设置（实施方案 §3.6 / §5.x）：
 * - 后台：按 group 分组读写（basic/contact/social/seo/notify/content）；
 * - 前台：GET /api/site/settings 缓存 10 分钟（排除敏感组）。
 */
@Service
public class SiteSettingsService {

    /** 前台公开组（不含敏感配置） */
    private static final List<String> PUBLIC_GROUPS =
            List.of("basic", "contact", "social", "seo", "content");

    private static final long CACHE_TTL_MILLIS = 10 * 60 * 1000;

    private final SiteSettingsMapper settingsMapper;

    // 前台缓存：{value, cachedAt}
    private volatile Map<String, Object> publicCache;
    private volatile long publicCacheAt;

    public SiteSettingsService(SiteSettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
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

    /** 后台批量更新。 */
    @Transactional
    public void updateAll(List<KeyValueRequest> items) {
        if (items == null) {
            return;
        }
        for (KeyValueRequest item : items) {
            if (item.key() == null || item.key().isBlank()) {
                throw BusinessException.badRequest("设置项 key 不能为空");
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
