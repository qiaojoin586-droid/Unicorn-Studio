package com.unicorn.studio.service;

import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.SiteSettings;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 敏感词过滤（初版字符串匹配，词库存 site_settings key=notify.sensitive_words，逗号分隔）。
 * 命中即视为内容违规；后续可升级 AC 自动机 / 第三方库。
 * <p>
 * 词库是「几乎永不变化」的 ~100 字节配置，却会在最热的两条写路径（评论/留言）上被每次重查库 + 重 split，
 * 故加字段级 memoize（volatile 字段 + 写后失效），消除重复读（Redis 方案 §2.5 / §11 P-1 第一项）。
 */
@Service
public class SensitiveWordService {

    /** site_settings 键名（SiteSettingsService 写后据此失效） */
    public static final String KEY = "notify.sensitive_words";

    /**
     * 本地缓存的存活时长（B-27③）：5 分钟。
     * <p>
     * 此前这份缓存是<b>进程内常驻、无 TTL、无上限</b>，而 {@code invalidate()} 只清<b>当前实例</b> ——
     * 即多实例部署时，A 实例改了词库，B 实例的旧词库会<b>永久生效</b>
     * （{@code SiteSettingsService} 的失效只在本实例写入且 key 命中时才调用 invalidate）。
     * <p>
     * 加 TTL 之后，最坏情况的陈旧窗口被限制在 5 分钟而非「直到重启」。
     * 单实例部署下这个 TTL 不改变行为（写入仍会立即 invalidate，走的是同一条路径）。
     * <b>多实例部署前</b>仍应换成 Redis 发布订阅之类的跨实例失效广播 —— TTL 只是兜底，不是等价替代。
     */
    private static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private final SiteSettingsMapper settingsMapper;

    /** 词库本地缓存：null 表示未加载/已失效；命中且未过期时零查库。 */
    private volatile List<String> cachedWords;
    private volatile long cachedAtMillis;

    public SensitiveWordService(SiteSettingsMapper settingsMapper) {
        this.settingsMapper = settingsMapper;
    }

    /** 返回命中的第一个敏感词；无命中返回 null。 */
    public String findSensitive(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> words = loadWords();
        for (String word : words) {
            // 说明：这里是 O(词数) 的 contains 扫描。词表规模由 site_settings 决定，
            // 中等词表（数千词）× 每条评论 1000 字会可观。正解是 AC 自动机，
            // 但实现成本不低 —— 按《后端优化方案》B-27② 的结论：待词表规模真的上来再做。
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    public boolean containsSensitive(String text) {
        return findSensitive(text) != null;
    }

    /** 词库变更（SiteSettingsService.updateAll 写入 notify.sensitive_words）后调用，下次读取回源。 */
    public void invalidate() {
        cachedWords = null;
        cachedAtMillis = 0;
    }

    /**
     * 读取词库（带 TTL 的 memoize）。
     * <p>
     * <b>查库移到锁外（B-30②）</b>：原实现在 {@code synchronized} 块<b>内部</b>查库，
     * 于是「缓存刚失效时的第一个请求」会把后续所有并发的评论/留言请求<b>串行化</b>在
     * 这次数据库往返上 —— 而这两条恰恰是最热的写路径。
     * <p>
     * 改为「锁外查库 + 锁内只做赋值」：极端情况下（多个线程同时发现缓存过期）
     * 会有几次重复查库，代价是几次 KV 查询；换来的是热点路径不再互相阻塞。
     * 这个取舍对「几乎永不变化的 ~100 字节配置」是明显划算的。
     */
    private List<String> loadWords() {
        List<String> cached = cachedWords;
        if (cached != null && System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MILLIS) {
            return cached;
        }
        // 锁外查库：允许并发重复查询，换取不阻塞热点路径
        List<String> loaded = settingsMapper.findBySettingKey(KEY)
                .map(SiteSettings::getSettingValue)
                .map(v -> Arrays.stream(v.split("[,，]"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList())
                .orElse(List.of());
        cachedWords = loaded;
        cachedAtMillis = System.currentTimeMillis();
        return loaded;
    }
}
