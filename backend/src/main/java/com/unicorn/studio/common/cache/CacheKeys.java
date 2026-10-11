package com.unicorn.studio.common.cache;

import java.util.List;

/**
 * 缓存键常量（Redis 方案 §5.1 键命名规范）。
 * <p>
 * 集中维护，供「读端」与「写端失效」跨服务引用，避免手写字面量导致失效键与写入键不一致。
 * 统一前缀 {@code cache:}；A/B 级数据量小、TTL 短，失效用「显式 DEL 明确 key」即可（§5.4）。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    /** 页脚页面 VO（A3，{@code cache:footer:page}） */
    public static final String FOOTER_PAGE = "cache:footer:page";

    /** 关于我们页面 VO（A2，{@code cache:about:page}） */
    public static final String ABOUT_PAGE = "cache:about:page";

    /** 加入我们页面 VO（A2，{@code cache:join:page}） */
    public static final String JOIN_PAGE = "cache:join:page";

    /**
     * 由 {@code SiteSettingsService.updateAll} 写入通道驱动的**全部页面级 VO 缓存键**。
     * <p>
     * 与 {@link #ALL_BG_KEYS} 同一条教训：失效方一律**整体遍历本列表**，不要手写若干个 key。
     * 手写的代价是「新增页面时静默漏配」，而漏配的表现恰恰是「**后台保存成功了、前台却不变**」，
     * 排查成本极高。{@code cache:join:page} 就是这样漏掉的（V17）：保存时只清了组级缓存
     * 与页脚/关于页 VO，加入我们的 VO 缓存照旧存活到 30 min TTL 到期。
     * <p>
     * ⚠️ 今后任何「由 site_settings 组装、且带独立 VO 缓存」的新页面，都必须加进本列表。
     */
    public static final List<String> PAGE_VO_KEYS = List.of(FOOTER_PAGE, ABOUT_PAGE, JOIN_PAGE);

    /** 首页轮播列表（A7，{@code cache:carousel:home}） */
    public static final String CAROUSEL_HOME = "cache:carousel:home";

    /** 页面背景前缀（A6），完整键 = 前缀 + pageKey（{@code about} / {@code work-detail}） */
    public static final String BG_PREFIX = "cache:bg:";

    /** 首页「主要招收方向」列表（B1，{@code cache:home:services}） */
    public static final String HOME_SERVICES = "cache:home:services";

    /** 首页「我们培养的能力」列表（B2，{@code cache:home:capabilities}） */
    public static final String HOME_CAPABILITIES = "cache:home:capabilities";

    /** 学习资料列表（B5，{@code cache:learning:list}） */
    public static final String LEARNING_LIST = "cache:learning:list";

    /**
     * 支持「单页背景」绑定的页面键。
     * <p>
     * <b>这里是唯一来源</b>：前台白名单（{@code PageBackgroundController.SUPPORTED_PAGES}）与
     * 后台缓存失效（{@code CarouselAdminService}）都取自它。此前两者各写一份，导致新增
     * {@code works} 页时白名单加了、失效点没加 —— 于是 {@code cache:bg:works} 只被预热、
     * 永不被清除，管理员改完「作品页」背景后前台最长 30 分钟看不到变化，且没有任何强制刷新手段。
     */
    public static final List<String> BG_PAGE_KEYS = List.of("about", "work-detail", "works");

    /**
     * 全部页面背景缓存键，供失效处整体遍历。
     * <p>
     * 失效一律用 {@code ALL_BG_KEYS} 而不是手写若干 {@code bg("...")}：后者在新增页面时
     * 会静默漏配，而「漏配」的表现是「后台改了前台没变」，排查成本远高于多清一个键。
     */
    public static final List<String> ALL_BG_KEYS =
            BG_PAGE_KEYS.stream().map(CacheKeys::bg).toList();

    /** 组装页面背景键。 */
    public static String bg(String pageKey) {
        return BG_PREFIX + pageKey;
    }

    // ---- 穿透防护（§4.1 方案 P1）：空值哨兵，独立前缀，短 TTL ----

    public static final String NULL_PREFIX = "cache:null:";

    public static String nullWork(String slug) {
        return NULL_PREFIX + "work:" + slug;
    }

    public static String nullNews(String slug) {
        return NULL_PREFIX + "news:" + slug;
    }

    public static String nullLearning(Long id) {
        return NULL_PREFIX + "learning:" + id;
    }

    // ---- 写热点（§4.4 浏览量计数）----

    public static final String VIEW_PREFIX = "cache:views:";
    public static final String VIEW_DIRTY = "cache:views:dirty";

    public static String view(String type, Long id) {
        return VIEW_PREFIX + type + ":" + id;
    }

    // ---- 会话：刷新令牌迁 Redis（§4.6）----
    // Redis 为唯一权威；DB 的 sys_user.refresh_token 列为历史残留，不再读写。

    public static final String REFRESH_PREFIX = "cache:refresh:";
    public static final String USER_TOKENS_PREFIX = "cache:user:tokens:";

    public static String refresh(String token) {
        return REFRESH_PREFIX + token;
    }

    public static String userTokens(Long userId) {
        return USER_TOKENS_PREFIX + userId;
    }

    // ---- 前台 token 撤销名单：退出登录用 ----
    // 键里放 token 的 SHA-256 而不是原文：① 原文 200+ 字符，键过长；
    // ② 名单要落 Redis 磁盘，放原文等于把一份可直接使用的凭证留在磁盘上；
    // ③ SHA-256 单向，从键无法反推 token。

    public static final String REVOKED_PUBLIC_PREFIX = "cache:public:revoked:";

    public static String revokedPublic(String tokenHash) {
        return REVOKED_PUBLIC_PREFIX + tokenHash;
    }

    // ---- 后台聚合（B11）：仪表盘短 TTL ----

    public static String dashboardSummary(int days) {
        return "cache:dashboard:summary:" + days;
    }

    // ---- 限流（§4.5）：与业务无关的 rate: 前缀（§5.1 分开前缀） ----

    public static final String RATE_PREFIX = "rate:";

    public static String rate(String dimension, String subject) {
        return RATE_PREFIX + dimension + ":" + subject;
    }

    // ---- AI 会话读缓存（§5.3：Redis 只做读缓存，MySQL 是唯一权威）----

    /** AI 会话删除时用的缓存键 */
    /**
     * 缓存键必须带 size：缓存里存的是"本次请求那几条"，而同一个用户可能用不同
     * pageSize 打开列表（抽屉默认 20、其它入口可能 50）。不带 size 就会在第二次
     * 用另一个 size 请求时命中上次那份长度不对的列表。
     */
    public static String aiSessions(Long userId, int size) {
        return "cache:ai:sessions:" + userId + ":" + size;
    }

    public static String aiMessages(String conversationId) {
        return "cache:ai:msgs:" + conversationId;
    }

    /** 清空对话时失效该用户全部 size 变体的会话列表缓存（读写键带 size，只能按模式删） */
    public static String aiSessionsPattern(Long userId) {
        return "cache:ai:sessions:" + userId + ":*";
    }

    /** 清空对话时失效该会话的全部消息缓存（size 有多种变体，只能按模式删） */
    public static String aiMessagesPattern(String conversationId) {
        return "cache:ai:msgs:" + conversationId + ":*";
    }
}
