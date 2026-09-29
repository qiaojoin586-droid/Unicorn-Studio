package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.vo.JoinPageVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「加入我们」页面内容（V17）：整页文案 + 社群二维码改为后台可维护。
 * <p>
 * 存储落在 site_settings 的 join 组，组内 key：
 * {@code join.eyebrow / join.title / join.subtitle / join.paragraphs / join.qr_image / join.qr_caption}
 * （paragraphs 以空行分隔的纯文本存放，读取时切分为列表）。这是单例页面配置而非内容集合，
 * 因此复用既有设置存储与写入通道，不新建表。
 * <p>
 * 兜底约定：缺失字段继承默认；可选字段的显式空字符串保持为空，
 * 数据库中尚无 join 组时继续使用原有默认文案与二维码。
 */
@Service
public class JoinPageService {

    /** site_settings 分组名 */
    public static final String GROUP = "join";

    private static final String KEY_EYEBROW = "join.eyebrow";
    private static final String KEY_TITLE = "join.title";
    private static final String KEY_SUBTITLE = "join.subtitle";
    private static final String KEY_PARAGRAPHS = "join.paragraphs";
    private static final String KEY_QR_IMAGE = "join.qr_image";
    private static final String KEY_QR_CAPTION = "join.qr_caption";

    private static final String DEFAULT_EYEBROW = "";
    private static final String DEFAULT_TITLE = "加入我们";
    private static final String DEFAULT_SUBTITLE = "欢迎加入独角兽工作室交流社群";
    private static final List<String> DEFAULT_PARAGRAPHS = List.of(
            "在这里我们分享前沿技术、实战项目与设计创意，共同成长、一起探索数字世界的更多可能。",
            "扫描下方二维码，即可加入社群与我们直接交流。");
    private static final String DEFAULT_QR_CAPTION = "QQ扫码加入社群";

    /** 正文段落条数上限（防误填导致页面过长） */
    private static final int MAX_PARAGRAPHS = 12;
    /** 单段字数上限 */
    private static final int MAX_PARAGRAPH_LENGTH = 500;

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** 默认二维码静态资源（与改造前 JoinPage.vue 的 import 保持一致，仅作兜底展示） */
    private static final String DEFAULT_QR_IMAGE = "";

    private final SiteSettingsService settingsService;
    private final CacheHelper cache;

    public JoinPageService(SiteSettingsService settingsService, CacheHelper cache) {
        this.settingsService = settingsService;
        this.cache = cache;
    }

    /** 读取页面内容（含默认值兜底）；Redis 缓存 30 min。 */
    public JoinPageVO load() {
        return cache.get(CacheKeys.JOIN_PAGE, JoinPageVO.class).orElseGet(() -> {
            JoinPageVO vo = assemble();
            cache.set(CacheKeys.JOIN_PAGE, vo, CACHE_TTL);
            return vo;
        });
    }

    private JoinPageVO assemble() {
        Map<String, String> kv = settingsService.listGroup(GROUP);
        return new JoinPageVO(
                valueOrDefault(kv.get(KEY_EYEBROW), DEFAULT_EYEBROW),
                requiredValueOrDefault(kv.get(KEY_TITLE), DEFAULT_TITLE),
                valueOrDefault(kv.get(KEY_SUBTITLE), DEFAULT_SUBTITLE),
                parseParagraphs(kv.get(KEY_PARAGRAPHS)),
                valueOrDefault(kv.get(KEY_QR_IMAGE), DEFAULT_QR_IMAGE),
                valueOrDefault(kv.get(KEY_QR_CAPTION), DEFAULT_QR_CAPTION));
    }

    /**
     * 保存页面内容。
     * <p>
     * 写库走既有设置写入通道 {@link SiteSettingsService#updateAll}（它会统一清掉公开聚合、组级快照
     * 与页面级 VO 三层缓存），**此外这里再显式失效本页自己的 {@code cache:join:page}**：
     * 该键由本类的 {@link #load()} 独占写入，就该由本类负责失效，归属清晰，
     * 不必依赖「远端某个共享失效列表恰好记得我」。
     * <p>
     * 历史教训（V17）：save() 原先只依赖 updateAll 的统一失效，而那份列表当时漏了 JOIN_PAGE，
     * 于是「后台保存成功、前台最长 30 分钟不变」—— 专有接口 {@code GET /api/join} 一直吐旧 VO。
     * 重复删同一个 key 是幂等的（DEL），代价可忽略，故两处都保留。
     */
    @Transactional
    public void save(JoinPageVO form) {
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
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_PARAGRAPHS, writeParagraphs(normalizeParagraphs(form.paragraphs()))),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_QR_IMAGE, nullToEmpty(form.qrImage())),
                new SiteSettingsService.KeyValueRequest(GROUP, KEY_QR_CAPTION, nullToEmpty(form.qrCaption()))));
        // 本页自己的页面级 VO 缓存：事务提交后失效，避免「清完又回填旧值」的竞态
        cache.evictAfterCommit(CacheKeys.JOIN_PAGE);
    }

    /**
     * 解析正文段落。未配置（null）→ 默认段落；
     * 显式配置为空内容（如空串）→ 视为「不展示正文」，此时不回填默认段落。
     */
    private List<String> parseParagraphs(String raw) {
        if (raw == null) {
            return DEFAULT_PARAGRAPHS;
        }
        List<String> paragraphs = splitParagraphs(raw);
        return paragraphs;
    }

    /** 按空行切分段落，裁剪首尾空白，丢弃空段。 */
    private static List<String> splitParagraphs(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\\s*\n");
        for (String block : blocks) {
            String text = block.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_PARAGRAPH_LENGTH) {
                text = text.substring(0, MAX_PARAGRAPH_LENGTH);
            }
            result.add(text);
            if (result.size() >= MAX_PARAGRAPHS) {
                break;
            }
        }
        return result;
    }

    /** 丢弃空段，避免脏数据入库或渲染出空段落块。 */
    private static List<String> normalizeParagraphs(List<String> paragraphs) {
        List<String> result = new ArrayList<>();
        if (paragraphs == null) {
            return result;
        }
        for (String paragraph : paragraphs) {
            if (paragraph == null) {
                continue;
            }
            String text = paragraph.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > MAX_PARAGRAPH_LENGTH) {
                text = text.substring(0, MAX_PARAGRAPH_LENGTH);
            }
            result.add(text);
            if (result.size() >= MAX_PARAGRAPHS) {
                break;
            }
        }
        return result;
    }

    /** 段落列表 → 以空行分隔的纯文本，便于后台在多行文本框里编辑。 */
    private static String writeParagraphs(List<String> paragraphs) {
        return String.join("\n\n", paragraphs);
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
