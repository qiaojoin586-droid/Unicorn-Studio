package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.vo.AboutPageVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「关于我们」页面内容（V12）：整页文案改为后台可维护。
 * <p>
 * 存储落在 site_settings 的 about 组，组内 key：
 * {@code about.eyebrow / about.title / about.subtitle / about.content / about.image_url / about.stats}
 * （stats 为 JSON 数组，元素 {@code {value,label}}）。这是单例页面配置而非内容集合，
 * 因此复用既有设置存储与写入通道，不新建表。
 * <p>
 * 兜底约定：任何字段缺失或为空时回落到「页面改造前的原始文案」，
 * 故数据库中尚无 about 组时，前台表现与改造前完全一致（零配置零回归）。
 */
@Service
public class AboutPageService {

    /** site_settings 分组名 */
    public static final String GROUP = "about";

    private static final String KEY_EYEBROW = "about.eyebrow";
    private static final String KEY_TITLE = "about.title";
    private static final String KEY_SUBTITLE = "about.subtitle";
    private static final String KEY_CONTENT = "about.content";
    private static final String KEY_IMAGE = "about.image_url";
    private static final String KEY_STATS = "about.stats";

    private static final String DEFAULT_EYEBROW = "About Us";
    private static final String DEFAULT_TITLE = "关于独角兽工作室";
    private static final String DEFAULT_SUBTITLE = "一群热爱技术与创造力的伙伴，致力于在数字世界中创造价值";
    private static final String DEFAULT_CONTENT = """
            独角兽工作室是一支充满激情的技术团队，专注于为客户提供高质量的数字产品与服务。我们相信，优秀的技术结合出色的设计，能够为用户带来非凡的体验。

            自创立以来，我们已为众多企业提供了 Web 开发、移动应用、大模型学术研究等方面的专业服务，赢得了客户的广泛认可与好评。""";
    private static final List<AboutPageVO.Stat> DEFAULT_STATS = List.of(
            new AboutPageVO.Stat("50+", "完成项目"),
            new AboutPageVO.Stat("30+", "合作客户"),
            new AboutPageVO.Stat("5+", "年行业经验"));

    /** 统计指标条数上限（防误填导致首屏拥挤） */
    private static final int MAX_STATS = 8;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SiteSettingsService settingsService;

    public AboutPageService(SiteSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 读取页面内容（含默认值兜底）。 */
    public AboutPageVO load() {
        Map<String, String> kv = settingsService.listGroup(GROUP);
        return new AboutPageVO(
                valueOrDefault(kv.get(KEY_EYEBROW), DEFAULT_EYEBROW),
                valueOrDefault(kv.get(KEY_TITLE), DEFAULT_TITLE),
                valueOrDefault(kv.get(KEY_SUBTITLE), DEFAULT_SUBTITLE),
                valueOrDefault(kv.get(KEY_CONTENT), DEFAULT_CONTENT),
                valueOrDefault(kv.get(KEY_IMAGE), null),
                parseStats(kv.get(KEY_STATS)));
    }

    /** 保存页面内容（写入 site_settings.about 组，走既有设置写入通道并失效前台缓存）。 */
    @Transactional
    public void save(AboutPageVO form) {
        if (form == null) {
            throw BusinessException.badRequest("内容不能为空");
        }
        if (isBlank(form.title())) {
            throw BusinessException.badRequest("主标题不能为空");
        }
        settingsService.updateAll(List.of(
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_EYEBROW, nullToEmpty(form.eyebrow())),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_TITLE, form.title().trim()),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_SUBTITLE, nullToEmpty(form.subtitle())),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_CONTENT, nullToEmpty(form.content())),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_IMAGE, nullToEmpty(form.imageUrl())),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_STATS, writeStats(normalizeStats(form.stats())))));
    }

    private List<AboutPageVO.Stat> parseStats(String json) {
        if (isBlank(json)) {
            // 未配置 → 默认指标
            return DEFAULT_STATS;
        }
        try {
            // 显式配置为 [] 表示「前台不展示统计区域」，此时不回填默认值
            return normalizeStats(JSON.readValue(json, new TypeReference<List<AboutPageVO.Stat>>() { }));
        } catch (Exception e) {
            // 配置损坏不应让页面报错，回落默认指标
            return DEFAULT_STATS;
        }
    }

    /** 丢弃空行、裁剪空白，避免脏数据入库或渲染出空统计块 */
    private static List<AboutPageVO.Stat> normalizeStats(List<AboutPageVO.Stat> stats) {
        List<AboutPageVO.Stat> result = new ArrayList<>();
        if (stats == null) {
            return result;
        }
        for (AboutPageVO.Stat stat : stats) {
            if (stat == null) {
                continue;
            }
            String value = stat.value() == null ? "" : stat.value().trim();
            String label = stat.label() == null ? "" : stat.label().trim();
            if (value.isEmpty() && label.isEmpty()) {
                continue;
            }
            result.add(new AboutPageVO.Stat(value, label));
            if (result.size() >= MAX_STATS) {
                break;
            }
        }
        return result;
    }

    private static String writeStats(List<AboutPageVO.Stat> stats) {
        try {
            return JSON.writeValueAsString(stats);
        } catch (Exception e) {
            throw BusinessException.badRequest("统计指标格式不正确");
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
