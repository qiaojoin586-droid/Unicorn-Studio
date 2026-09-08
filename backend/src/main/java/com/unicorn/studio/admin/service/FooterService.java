package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.vo.FooterVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页脚配置（V13）：整块页脚文案 / 链接 / 社交图标改为后台可维护。
 * <p>
 * 存储落在 site_settings 的 footer 组，组内 key：
 * {@code footer.brand_name / brand_intro / show_particles / links_title / links /
 * social_title / socials / email / email_label / copyright_left / copyright_right}
 * （links、socials 为 JSON 数组）。与「关于我们」一致，这是单例页面配置而非内容集合，
 * 因此复用既有设置存储与写入通道，不新建表。
 * <p>
 * 兜底约定：任何字段缺失或为空时回落到「页脚改造前的原始文案」，
 * 故数据库中尚无 footer 组时，前台表现与改造前完全一致（零配置零回归）。
 */
@Service
public class FooterService {

    /** site_settings 分组名 */
    public static final String GROUP = "footer";

    private static final String KEY_BRAND_NAME = "footer.brand_name";
    private static final String KEY_BRAND_INTRO = "footer.brand_intro";
    private static final String KEY_SHOW_PARTICLES = "footer.show_particles";
    private static final String KEY_LINKS_TITLE = "footer.links_title";
    private static final String KEY_LINKS = "footer.links";
    private static final String KEY_SOCIAL_TITLE = "footer.social_title";
    private static final String KEY_SOCIALS = "footer.socials";
    private static final String KEY_EMAIL = "footer.email";
    private static final String KEY_EMAIL_LABEL = "footer.email_label";
    private static final String KEY_COPYRIGHT_LEFT = "footer.copyright_left";
    private static final String KEY_COPYRIGHT_RIGHT = "footer.copyright_right";

    private static final String DEFAULT_BRAND_NAME = "独角兽工作室";
    /** 两行文案，\n 在后台表单与前台渲染中都保留为换行 */
    private static final String DEFAULT_BRAND_INTRO =
            "用技术与创意，打造卓越的数字产品。\n我们专注于AI智能开发、Agent开发与大模型学术研究。";
    /** 左侧装饰粒子动效：默认关闭，严格保持页脚改造前的样式 */
    private static final boolean DEFAULT_SHOW_PARTICLES = false;
    private static final String DEFAULT_LINKS_TITLE = "快速链接";
    private static final String DEFAULT_SOCIAL_TITLE = "关注我们";
    private static final String DEFAULT_EMAIL = "1351094759@qq.com";
    private static final String DEFAULT_EMAIL_LABEL = "邮箱：";
    /** {year} 占位符由前台替换为当前年份，避免版权年份被冻结 */
    private static final String DEFAULT_COPYRIGHT_LEFT = "© {year} 独角兽工作室 · 用创意点亮未来";
    private static final String DEFAULT_COPYRIGHT_RIGHT = "每个人都有一颗炽热的 ❤️ and You";

    private static final List<FooterVO.Link> DEFAULT_LINKS = List.of(
            new FooterVO.Link("首页", "/", false),
            new FooterVO.Link("作品", "/works", false),
            new FooterVO.Link("关于我们", "/about", false),
            new FooterVO.Link("学习资料", "/learning", false));

    private static final List<FooterVO.Social> DEFAULT_SOCIALS = List.of(
            new FooterVO.Social("github", "", "https://github.com", "GitHub"),
            new FooterVO.Social("qq", "", "https://qq.com", "QQ"));

    /** 列表条数上限（防误填导致页脚过高） */
    private static final int MAX_LINKS = 20;
    private static final int MAX_SOCIALS = 12;

    /** 形如 scheme: 的前缀；仅放行 http/https/mailto/tel，其余（javascript:、data: 等）一律拒绝 */
    private static final Pattern SCHEME_PREFIX = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*):");
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto", "tel");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SiteSettingsService settingsService;

    public FooterService(SiteSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 读取页脚配置（含默认值兜底）。 */
    public FooterVO load() {
        Map<String, String> kv = settingsService.listGroup(GROUP);
        return new FooterVO(
                valueOrDefault(kv.get(KEY_BRAND_NAME), DEFAULT_BRAND_NAME),
                valueOrDefault(kv.get(KEY_BRAND_INTRO), DEFAULT_BRAND_INTRO),
                parseBoolean(kv.get(KEY_SHOW_PARTICLES), DEFAULT_SHOW_PARTICLES),
                valueOrDefault(kv.get(KEY_LINKS_TITLE), DEFAULT_LINKS_TITLE),
                parseLinks(kv.get(KEY_LINKS)),
                valueOrDefault(kv.get(KEY_SOCIAL_TITLE), DEFAULT_SOCIAL_TITLE),
                parseSocials(kv.get(KEY_SOCIALS)),
                valueOrDefault(kv.get(KEY_EMAIL), DEFAULT_EMAIL),
                valueOrDefault(kv.get(KEY_EMAIL_LABEL), DEFAULT_EMAIL_LABEL),
                valueOrDefault(kv.get(KEY_COPYRIGHT_LEFT), DEFAULT_COPYRIGHT_LEFT),
                valueOrDefault(kv.get(KEY_COPYRIGHT_RIGHT), DEFAULT_COPYRIGHT_RIGHT));
    }

    /** 保存页脚配置（写入 site_settings.footer 组，走既有设置写入通道并失效前台缓存）。 */
    @Transactional
    public void save(FooterVO form) {
        if (form == null) {
            throw BusinessException.badRequest("内容不能为空");
        }
        if (isBlank(form.brandName())) {
            throw BusinessException.badRequest("工作室名称不能为空");
        }
        // 先做严格校验（失败即整体不落库），再统一写入
        List<FooterVO.Link> links = filterLinks(form.links(), true);
        List<FooterVO.Social> socials = filterSocials(form.socials(), true);

        settingsService.updateAll(List.of(
                kv(KEY_BRAND_NAME, form.brandName().trim()),
                kv(KEY_BRAND_INTRO, nullToEmpty(form.brandIntro())),
                kv(KEY_SHOW_PARTICLES, Boolean.toString(form.showParticles())),
                kv(KEY_LINKS_TITLE, nullToEmpty(form.linksTitle())),
                kv(KEY_LINKS, writeJson(links)),
                kv(KEY_SOCIAL_TITLE, nullToEmpty(form.socialTitle())),
                kv(KEY_SOCIALS, writeJson(socials)),
                kv(KEY_EMAIL, nullToEmpty(form.email())),
                kv(KEY_EMAIL_LABEL, nullToEmpty(form.emailLabel())),
                kv(KEY_COPYRIGHT_LEFT, nullToEmpty(form.copyrightLeft())),
                kv(KEY_COPYRIGHT_RIGHT, nullToEmpty(form.copyrightRight()))));
    }

    // ---------- 读：解析失败/脏数据一律静默降级，绝不让前台报错 ----------

    private List<FooterVO.Link> parseLinks(String json) {
        if (isBlank(json)) {
            // 未配置 → 默认链接
            return DEFAULT_LINKS;
        }
        try {
            // 显式配置为 [] 表示「前台不展示快捷链接」，此时不回填默认值
            return filterLinks(JSON.readValue(json, new TypeReference<List<FooterVO.Link>>() { }), false);
        } catch (Exception e) {
            return DEFAULT_LINKS;
        }
    }

    private List<FooterVO.Social> parseSocials(String json) {
        if (isBlank(json)) {
            return DEFAULT_SOCIALS;
        }
        try {
            return filterSocials(JSON.readValue(json, new TypeReference<List<FooterVO.Social>>() { }), false);
        } catch (Exception e) {
            return DEFAULT_SOCIALS;
        }
    }

    // ---------- 写：strict=true 时对非法行抛 400 ----------

    private static List<FooterVO.Link> filterLinks(List<FooterVO.Link> raw, boolean strict) {
        List<FooterVO.Link> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (FooterVO.Link link : raw) {
            if (link == null) {
                continue;
            }
            String label = trim(link.label());
            String url = trim(link.url());
            // 整行空白（后台新增后未填）直接忽略，不算错误
            if (label.isEmpty() && url.isEmpty()) {
                continue;
            }
            if (label.isEmpty()) {
                if (strict) {
                    throw BusinessException.badRequest("链接文字不能为空");
                }
                continue;
            }
            if (!isValidUrl(url)) {
                if (strict) {
                    throw BusinessException.badRequest("链接地址格式不正确：" + url);
                }
                continue;
            }
            result.add(new FooterVO.Link(label, url, link.newTab()));
            if (result.size() >= MAX_LINKS) {
                break;
            }
        }
        return result;
    }

    private static List<FooterVO.Social> filterSocials(List<FooterVO.Social> raw, boolean strict) {
        List<FooterVO.Social> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (FooterVO.Social social : raw) {
            if (social == null) {
                continue;
            }
            String icon = trim(social.icon());
            String iconUrl = trim(social.iconUrl());
            String url = trim(social.url());
            String title = trim(social.title());
            // 整行空白直接忽略
            if (icon.isEmpty() && iconUrl.isEmpty() && url.isEmpty() && title.isEmpty()) {
                continue;
            }
            if (url.isEmpty()) {
                if (strict) {
                    throw BusinessException.badRequest("社交链接地址不能为空");
                }
                continue;
            }
            if (!isValidUrl(url)) {
                if (strict) {
                    throw BusinessException.badRequest("社交链接地址格式不正确：" + url);
                }
                continue;
            }
            // 自定义图标地址同样只接受站内路径 / 允许的 scheme（拒绝 javascript: 等）
            if (!iconUrl.isEmpty() && !isValidUrl(iconUrl)) {
                if (strict) {
                    throw BusinessException.badRequest("自定义图标地址格式不正确：" + iconUrl);
                }
                continue;
            }
            result.add(new FooterVO.Social(icon, iconUrl, url, title.isEmpty() ? url : title));
            if (result.size() >= MAX_SOCIALS) {
                break;
            }
        }
        return result;
    }

    /**
     * 地址合法性：站内路径（{@code /x}）、锚点（{@code #x}）、无 scheme 的相对路径，
     * 以及 http/https/mailto/tel 绝对地址；显式拒绝 {@code //host} 与其它 scheme。
     */
    private static boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (url.startsWith("//")) {
            return false;
        }
        if (url.startsWith("/") || url.startsWith("#")) {
            return true;
        }
        Matcher matcher = SCHEME_PREFIX.matcher(url);
        if (matcher.find()) {
            return ALLOWED_SCHEMES.contains(matcher.group(1).toLowerCase());
        }
        // 无 scheme 的相对路径（如 works、works/1）
        return true;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw BusinessException.badRequest("列表格式不正确");
        }
    }

    private static SiteSettingsService.KeyValueRequest kv(String key, String value) {
        return new SiteSettingsService.KeyValueRequest(GROUP, key, value);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        String trimmed = value.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed);
    }

    private static String valueOrDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
