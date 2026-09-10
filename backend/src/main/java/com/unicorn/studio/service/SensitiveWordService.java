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

    private final SiteSettingsMapper settingsMapper;

    /** 词库本地缓存：null 表示未加载/已失效；命中时零查库。 */
    private volatile List<String> cachedWords;

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
    }

    private List<String> loadWords() {
        List<String> cached = cachedWords;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedWords == null) {
                cachedWords = settingsMapper.findBySettingKey(KEY)
                        .map(SiteSettings::getSettingValue)
                        .map(v -> Arrays.stream(v.split("[,，]"))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList())
                        .orElse(List.of());
            }
            return cachedWords;
        }
    }
}
