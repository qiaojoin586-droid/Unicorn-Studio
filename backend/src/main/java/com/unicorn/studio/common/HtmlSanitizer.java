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
 * - 保存与前台输出前各跑一次 clean()（双保险）。
 */
public final class HtmlSanitizer {

    private static final Set<String> ALLOWED_DOMAINS = new HashSet<>();

    /** 配置外链图白名单域（application.yml app.upload.image-allow-origins） */
    public static void configureAllowedOrigins(String origins) {
        ALLOWED_DOMAINS.clear();
        if (origins != null) {
            for (String origin : origins.split(",")) {
                String host = origin.trim();
                if (!host.isEmpty()) {
                    ALLOWED_DOMAINS.add(host);
                }
            }
        }
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
            // 先走内置规则（标签/属性/协议白名单）
            if (!super.isSafeAttribute(tagName, el, attr)) {
                return false;
            }
            // 域名白名单：仅 img@src 追加校验
            if ("img".equals(tagName) && "src".equals(attr.getKey())) {
                try {
                    URL url = new URL(attr.getValue());
                    return url.getPath().startsWith("/uploads/")
                            || ALLOWED_DOMAINS.contains(url.getHost());
                } catch (java.net.MalformedURLException e) {
                    return false;
                }
            }
            return true;
        }
    };

    private HtmlSanitizer() {
    }

    /** 消毒富文本；null 输入返回 null。 */
    public static String clean(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        // jsoup 1.18：clean 返回净化后的 HTML 字符串
        return Jsoup.clean(html, "", SAFELIST);
    }
}
