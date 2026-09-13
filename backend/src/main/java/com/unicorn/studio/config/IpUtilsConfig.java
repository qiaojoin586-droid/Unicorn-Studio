package com.unicorn.studio.config;

import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.common.IpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 启动时把「可信代理跳数」注入 {@link IpUtils}（S-7）。
 * <p>
 * 配置项 {@code app.security.trusted-proxy-count}：
 * <ul>
 *   <li><b>0</b>（默认，安全值）：完全不信任 {@code X-Forwarded-For}，只用 {@code remoteAddr}。
 *       适合「应用可被直连」或拓扑不确定的场景。</li>
 *   <li><b>N &gt; 0</b>：信任最右 N 跳。必须等于<b>实际代理层数</b>。</li>
 * </ul>
 * <b>本项目的实际拓扑</b>：{@code server.address=127.0.0.1} 使应用只监听回环、必然经代理，
 * 故部署值应为 1（nginx 直连后端）。
 * <p>
 * ⚠️ 若拓扑是「nginx → Node 生产服务(frontend/server/index.js) → 后端」，代理层数为 <b>2</b>，
 * 此处必须配 2，否则取到的是 nginx 的 IP，全站将共用同一个限流桶。
 * 两条代理路径都已改为「追加」语义（nginx 用 {@code $proxy_add_x_forwarded_for}，
 * Node 见 {@code proxyToBackend}），与「从右往左取第 N 跳」的算法配套。
 */
@Configuration
public class IpUtilsConfig {

    private static final Logger log = LoggerFactory.getLogger(IpUtilsConfig.class);

    public IpUtilsConfig(@Value("${app.security.trusted-proxy-count:0}") int trustedProxyCount,
                         @Value("${app.upload.image-allow-origins:}") String imageAllowOrigins) {
        IpUtils.configureTrustedProxyCount(trustedProxyCount);

        if (trustedProxyCount <= 0) {
            log.info("客户端 IP 提取：不信任 X-Forwarded-For（trusted-proxy-count=0），仅使用 remoteAddr");
        } else {
            log.info("客户端 IP 提取：信任 X-Forwarded-For 最右 {} 跳作为客户端 IP。"
                    + "请确认该值与实际代理层数一致——配小会让全站共用一个限流桶，"
                    + "配大则等于采信攻击者提供的值。", trustedProxyCount);
        }

        // 富文本外链图白名单（B-23③）：这个配置此前【完全没有生效】——
        // HtmlSanitizer.configureAllowedOrigins 全仓无调用者，意味着
        // app.upload.image-allow-origins 填了也没用，富文本里的外链图恒被消毒规则拒绝。
        // 放在这里是因为它和上面那项一样，都是「启动时把配置灌进静态工具类」。
        HtmlSanitizer.configureAllowedOrigins(imageAllowOrigins);
        if (imageAllowOrigins == null || imageAllowOrigins.isBlank()) {
            log.info("富文本外链图白名单未配置：仅允许本站 /uploads/ 的图片");
        } else {
            log.info("富文本外链图白名单已生效：{}", imageAllowOrigins);
        }
    }
}
