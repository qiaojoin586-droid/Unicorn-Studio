package com.unicorn.studio.AIWorker.tool.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 站点页面地址（把站内路径变成访客能点的完整地址）。
 * <p>
 * <b>为什么必须是完整地址</b>：工具的返回值会进模型上下文、再由模型写进回答里。
 * 访客看到的是一段文字 —— 相对路径（{@code /works}）在回答里<b>没法点</b>，
 * 复制到地址栏也是错的。而完整地址（{@code https://xxx/works}）能直接点开。
 * <p>
 * <b>为什么基址来自配置而不是请求头</b>：{@code Host} / {@code X-Forwarded-Host}
 * 是<b>客户端可控</b>的 ⇒ 用它拼出给访客的链接，等于允许伪造出一个指向别处的地址（钓鱼）。
 * ⚠️ 所以基址必须是服务端自己定的配置。
 * <p>
 * <b>没配基址时怎么办</b>：原样返回相对路径（<b>绝不自己编一个域名</b>），
 * 并由调用方记一条 WARN —— 访客点不开总比点到一个假域名好。
 */
@Component
public class SiteUrls {

    /** 站点对外地址，形如 {@code https://unicorn-studio.top}（末尾斜杠会被去掉）。 */
    private final String baseUrl;

    public SiteUrls(@Value("${app.site.base-url:}") String baseUrl) {
        String v = baseUrl == null ? "" : baseUrl.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        this.baseUrl = v;
    }

    /**
     * 把站内路径拼成完整地址。
     * <p>
     * ⚠️ <b>已经是完整地址的原样返回</b>：{@code learning_resources.url} 这类字段
     * 存的就是外部链接（前台直接拿它当 {@code href} 用）⇒ 再拼一次基址就成���
     * {@code https://oursitehttps://external.com} 这种废地址。
     *
     * @param path 站内路径（{@code /works/xxx}）或完整地址
     * @return 完整地址；没配基址且给的是站内路径时，原样返回相对路径
     */
    public String absolute(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        if (baseUrl.isEmpty()) {
            return p;      // ⚠️ 没配基址 ⇒ 保持相对路径（调用方负责记日志提示）
        }
        return p.startsWith("/") ? baseUrl + p : baseUrl + "/" + p;
    }

    /** 是否配了基址。没配的话工具只能给相对地址。 */
    public boolean hasBaseUrl() {
        return !baseUrl.isEmpty();
    }

    /** 配置里的基址（用于日志与后台展示）。 */
    public String baseUrl() {
        return baseUrl;
    }
}
