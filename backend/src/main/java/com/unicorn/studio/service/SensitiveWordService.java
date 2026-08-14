package com.unicorn.studio.service;

import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.SiteSettings;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 敏感词过滤（初版字符串匹配，词库存 site_settings key=notify.sensitive_words，逗号分隔）。
 * 命中即视为内容违规；后续可升级 AC 自动机 / 第三方库。
 */
@Service
public class SensitiveWordService {

    private static final String KEY = "notify.sensitive_words";

    private final SiteSettingsMapper settingsMapper;

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

    private List<String> loadWords() {
        return settingsMapper.findBySettingKey(KEY)
                .map(SiteSettings::getSettingValue)
                .map(v -> Arrays.stream(v.split("[,，]"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList())
                .orElse(List.of());
    }
}
