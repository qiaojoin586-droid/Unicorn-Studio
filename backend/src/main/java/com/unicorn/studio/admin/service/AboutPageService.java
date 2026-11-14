package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.cache.CacheObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.vo.AboutPageVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
 * 兜底约定：缺失字段继承默认；可选字段的显式空字符串保持为空，
 * 数据库中尚无 about 组时使用工作室介绍默认文案，统计指标留空，已有值继续保留。
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
    private static final String DEFAULT_SUBTITLE = "我们是一群热爱技术与创造的伙伴，在学习、研究与真实项目中，探索想法的更多可能。";
    private static final String DEFAULT_CONTENT = """
            独角兽工作室关注 AI 智能应用、Agent 开发与大模型学术研究，也关注每一个想法如何成为可以体验的作品。

            我们希望把学习与实践连接起来：带着问题探索技术，在交流中分享经验，与志同道合的伙伴一起成长。""";
    private static final List<AboutPageVO.Stat> DEFAULT_STATS = List.of();

    /** 统计指标条数上限（防误填导致首屏拥挤） */
    private static final int MAX_STATS = 8;

    private static final ObjectMapper JSON = CacheObjectMapper.create();

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final SiteSettingsService settingsService;
    private final CacheHelper cache;

    public AboutPageService(SiteSettingsService settingsService, CacheHelper cache) {
        this.settingsService = settingsService;
        this.cache = cache;
    }

    /** 读取页面内容（含默认值兜底）；Redis 缓存 30 min（A2）。 */
    public AboutPageVO load() {
        var snapshot = cache.snapshot(CacheKeys.ABOUT_PAGE);
        return snapshot.get(AboutPageVO.class).orElseGet(() -> {
            AboutPageVO vo = assemble();
            snapshot.set(vo, CACHE_TTL);
            return vo;
        });
    }

    private AboutPageVO assemble() {
        Map<String, String> kv = settingsService.listGroup(GROUP);
        return new AboutPageVO(
                valueOrDefault(kv.get(KEY_EYEBROW), DEFAULT_EYEBROW),
                requiredValueOrDefault(kv.get(KEY_TITLE), DEFAULT_TITLE),
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

    private static String requiredValueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
