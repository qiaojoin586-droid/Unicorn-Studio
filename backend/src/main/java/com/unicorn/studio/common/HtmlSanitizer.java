package com.unicorn.studio.common;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/**
 * 富文本消毒（jsoup 白名单）：
 * - 允许常用排版标签，script/事件属性一律清除；
 * - img src 仅允许本站 /uploads/ 或白名单域（防外链跟踪；addProtocols 只能限协议，
 *   域名校验通过继承覆盖 isSafeAttribute 实现——Safelist 无公开 addAttributeEvaluator 接口）；
 * - <b>只在写入路径消毒</b>（WorkAdminService / NewsAdminService / LearningAdminService 保存前）。
 *   读取路径不重复消毒 —— 详见 {@link #clean} 的说明。
 */
public final class HtmlSanitizer {

    /**
     * 正文长度上限（B-23）：约 20 万字符。
     * <p>
     * 为什么必须有：{@code content} 列是 MEDIUMTEXT（上限 16MB），而 jsoup 的 {@code clean()}
     * 会先把整段 HTML 解析成<b>完整 DOM</b>（每个标签 / 属性 / 文本节点各一个对象），
     * 峰值堆可达原串的数倍。一次 16MB 的提交就能让 DOM 化吃掉数百 MB 堆 ——
     * 而本项目未设 {@code -Xmx}（JVM 默认堆是物理内存的 1/4）。
     * <p>
     * 取值依据：正常富文本正文远小于此（一篇长文含排版约 2～5 万字符），
     * 20 万字符对真实内容足够宽裕，同时把最坏情况的 DOM 化开销限制在可控范围。
     * <p>
     * <b>为什么只在 {@link #clean} 里校验，而不在表单 DTO 上加 {@code @Size}</b>：
     * 文档建议 DTO 也加一份，但实施时确认了两件事 ——
     * ① 三个富文本表单（Work/News/Learning）的控制器用的是裸 {@code @RequestBody}，<b>没有
     * {@code @Valid}</b>，只加 {@code @Size} 根本不会生效，必须连带改变 6 个端点的校验行为；
     * ② 这三条写路径<b>全部</b>经过 {@code clean()}，这里已是完整的卡点。
     * 在完整卡点上校验一次，好过在两个地方各写一份同样的限制 ——
     * 后者正是本项目已经吃过亏的模式（B-03 的白名单与失效点各写一份、B-28 的注释与实现不符）。
     * 两层的执行时机其实也只差「Jackson 反序列化之后」这一点点，收益不足以抵消重复定义的成本。
     */
    public static final int MAX_CONTENT_LENGTH = 200_000;

    /**
     * 外链图白名单域。
     * <p>
     * 用 {@code volatile} + 整体替换不可变集合，而不是在可变 {@code HashSet} 上
     * {@code clear()} + {@code add()}（B-23③）：后者在「配置写入」与「请求线程读取」之间
     * 没有 happens-before 保证，请求可能读到清空后、尚未装填完的中间状态；
     * 而且在遍历中并发修改会抛 {@code ConcurrentModificationException}。
     * 配置是启动时设一次的极小集合，整体替换的代价可以忽略。
     */
    private static volatile Set<String> allowedDomains = Set.of();

    /** 配置外链图白名单域（application.yml app.upload.image-allow-origins）。由启动类调用。 */
    public static void configureAllowedOrigins(String origins) {
        if (origins == null || origins.isBlank()) {
            allowedDomains = Set.of();
            return;
        }
        Set<String> hosts = new HashSet<>();
        for (String origin : origins.split(",")) {
            String host = origin.trim();
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        allowedDomains = Set.copyOf(hosts);
    }

    private static final Safelist SAFELIST = new Safelist(Safelist.relaxed()
            .addTags("img", "h1", "h2", "h3", "h4", "table", "thead", "tbody", "tr", "td", "th", "code", "pre", "span")
            .addAttributes("a", "href", "target", "rel")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("code", "class")
            .addProtocols("a", "href", "http", "https")
            .addProtocols("img", "src", "http", "https")) {
        @Override
        public boolean isSafeAttribute(String tagName, Element el, Attribute attr) {
            // 本站上传的相对路径图片（/uploads/...）：baseUri 为空时协议校验会误杀，
            // 且 new URL(相对路径) 抛 MalformedURLException，需在走内置规则前放行
            if ("img".equals(tagName) && "src".equals(attr.getKey())
                    && attr.getValue().startsWith("/uploads/")) {
                return true;
            }
            // 先走内置规则（标签/属性/协议白名单）
            if (!super.isSafeAttribute(tagName, el, attr)) {
                return false;
            }
            // 域名白名单：仅 img@src 追加校验
            if ("img".equals(tagName) && "src".equals(attr.getKey())) {
                try {
                    URL url = new URL(attr.getValue());
                    return url.getPath().startsWith("/uploads/")
                            || allowedDomains.contains(url.getHost());
                } catch (java.net.MalformedURLException e) {
                    return false;
                }
            }
            return true;
        }
    };

    private HtmlSanitizer() {
    }

    /**
     * 消毒富文本；null/空白输入原样返回。
     * <p>
     * <b>超长输入直接拒绝</b>（B-23）：在交给 jsoup 之前先判长度 —— 一旦开始解析，
     * DOM 化的内存开销就已经发生了，事后再判没有意义。这里抛 400 而不是截断：
     * 静默截断会让管理员以为正文保存成功了，实际内容被砍掉一半。
     * <p>
     * <b>为什么只在写入路径调用</b>：类注释此前写着「保存与前台输出前各跑一次（双保险）」，
     * 但实现从来只在写入路径消毒 —— 那是注释失实，不是实现漏了。
     * 现在明确选「只写不读」：
     * <ul>
     *   <li>写路径消毒后落库的内容已经是干净的，读路径再跑一次是重复计算；</li>
     *   <li>读路径（作品/新闻/学习资料详情）是<b>被缓存的高频路径</b>，
     *       每次详情请求都 DOM 化一遍正文，代价与收益不成比例；</li>
     *   <li>真要防「绕过写路径的脏数据」，正确做法是入库校验或数据库约束，
     *       而不是在每次读取时重算。</li>
     * </ul>
     * 保留写入路径消毒这一道，已覆盖所有正常入口。
     */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        if (html.length() > MAX_CONTENT_LENGTH) {
            throw BusinessException.badRequest(
                    "正文长度超过上限（" + MAX_CONTENT_LENGTH + " 字符），当前 " + html.length());
        }
        // jsoup 1.18：clean 返回净化后的 HTML 字符串
        return Jsoup.clean(html, "", SAFELIST);
    }
}
