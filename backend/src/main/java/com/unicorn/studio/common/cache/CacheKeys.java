package com.unicorn.studio.common.cache;

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

    // ---- 后台聚合（B11）：仪表盘短 TTL ----

    public static String dashboardSummary(int days) {
        return "cache:dashboard:summary:" + days;
    }

    // ---- 限流（§4.5）：与业务无关的 rate: 前缀（§5.1 分开前缀） ----

    public static final String RATE_PREFIX = "rate:";

    public static String rate(String dimension, String subject) {
        return RATE_PREFIX + dimension + ":" + subject;
    }
}
