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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 站点设置（实施方案 §3.6 / §5.x）：
 * - 后台：按 group 分组读写（basic/contact/social/seo/notify/content/about）；
 * - 前台：GET /api/site/settings 缓存 10 分钟（排除敏感组）。
 * - V12 起 about 组由「关于我们」专用页面读写（AboutPageService），走同一存储与写入通道。
 * <p>
 * 缓存只保留 Redis 一份（B-28）：{@link #listGroup} 走 {@code cache:site:group:{group}}（TTL 30 min），
 * {@link #publicSettings} 走 {@code cache:site:settings}（TTL 10 min），{@link #updateAll} 事务提交后一并失效。
 * 此前 {@link #publicSettings} 另有一份 JVM 内缓存（volatile 字段 + 双检锁）：同一份数据两处持有，
 * 且失效只清当前实例 —— 多实例部署时改了配置的实例清不掉别的实例，前台（{@code SiteSettingsController}
 * 对外暴露的 {@code GET /api/site/settings}）会读到旧值。去掉这份的代价只是每次读多一次 Redis RTT；
 * Redis 不可用时 {@link CacheHelper} 自动回源 MySQL，前台接口照常可用。
 * {@link #getValue} 仍不走缓存：它是运维运行时键，写后必须立刻读到新值（R5）。
 */
@Service
public class SiteSettingsService {

    /** 前台公开组（不含敏感配置） */
    private static final List<String> PUBLIC_GROUPS =
            List.of("basic", "contact", "social", "seo", "content");

    /** 组级快照缓存 key 前缀（§5.1） */
    private static final String GROUP_CACHE_PREFIX = "cache:site:group:";
    /** 前台公开设置快照 key（§5.1）；B-28 去掉 JVM 内缓存后，它是这份数据的唯一一份 */
    private static final String PUBLIC_CACHE_KEY = "cache:site:settings";
    private static final Duration SITE_TTL = Duration.ofMinutes(30);
    /** 前台公开设置 TTL：沿用去掉 JVM 缓存之前的 10 分钟，对外可见行为不变 */
    private static final Duration PUBLIC_SETTINGS_TTL = Duration.ofMinutes(10);
    private static final JavaType MAP_STRING_STRING =
            TypeFactory.defaultInstance().constructMapType(LinkedHashMap.class, String.class, String.class);
    /** {@code cache:site:settings} 的反序列化类型：{group -> {key -> value}} */
    private static final JavaType MAP_STRING_MAP_STRING = TypeFactory.defaultInstance().constructMapType(
            LinkedHashMap.class,
            TypeFactory.defaultInstance().constructType(String.class),
            MAP_STRING_STRING);

    private final SiteSettingsMapper settingsMapper;
    private final SensitiveWordService sensitiveWordService;
    private final CacheHelper cache;

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

    /**
     * 后台批量更新。
     * <p>
     * <b>单条语句完成（B-11）</b>：原实现是循环内「逐条查 + 逐条写」——
     * 页脚 11 个 key 要 22 条语句、关于页 6 个要 12 条，而事务持有时长与入参长度线性相关。
     * 这里先用一次遍历完成校验与装配，再交给 {@code batchUpsert} 一条语句写完。
     * <p>
     * 之所以能这样做：{@code setting_key} 上有唯一约束，冲突判定交给 MySQL 即可，
     * 不必先查一遍「存在与否」。语义与原先逐条实现一致（详见 {@code SiteSettingsMapper.batchUpsert}）。
     */
    @Transactional
    public void updateAll(List<KeyValueRequest> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        boolean sensitiveChanged = false;
        List<SiteSettings> toUpsert = new ArrayList<>(items.size());
        for (KeyValueRequest item : items) {
            if (item.key() == null || item.key().isBlank()) {
                throw BusinessException.badRequest("设置项 key 不能为空");
            }
            if (SensitiveWordService.KEY.equals(item.key())) {
                sensitiveChanged = true;
            }
            toUpsert.add(SiteSettings.builder()
                    .groupName(item.group())
                    .settingKey(item.key())
                    .settingValue(item.value())
                    .build());
        }
        // 校验全部通过后再落库：避免「前几条已写、中途抛异常」依赖事务回滚来兜底
        settingsMapper.batchUpsert(toUpsert);
        // Redis 缓存：事务提交后失效（§5.3 陷阱 1，避免「清完缓存又回滚/回填旧值」竞态）
        cache.evictAfterCommit(PUBLIC_CACHE_KEY);
        // 组级快照：组名是动态的，只能按前缀失效
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

    /** 前台公开设置（Redis 缓存 10 分钟；B-28 起不再有 JVM 内缓存）。 */
    public Map<String, Map<String, String>> publicSettings() {
        return cache.<Map<String, Map<String, String>>>get(PUBLIC_CACHE_KEY, MAP_STRING_MAP_STRING)
                .orElseGet(() -> {
                    Map<String, Map<String, String>> groups = new LinkedHashMap<>();
                    for (SiteSettings s : settingsMapper.findAllByOrderByGroupNameAscSettingKeyAsc()) {
                        if (!PUBLIC_GROUPS.contains(s.getGroupName())) {
                            continue;
                        }
                        groups.computeIfAbsent(s.getGroupName(), k -> new LinkedHashMap<>())
                                .put(s.getSettingKey(), s.getSettingValue());
                    }
                    cache.set(PUBLIC_CACHE_KEY, groups, PUBLIC_SETTINGS_TTL);
                    return groups;
                });
    }

    public record KeyValueRequest(String group, String key, String value) {
    }
}
