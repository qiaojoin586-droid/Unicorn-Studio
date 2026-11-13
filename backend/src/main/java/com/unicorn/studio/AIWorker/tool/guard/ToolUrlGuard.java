package com.unicorn.studio.AIWorker.tool.guard;

import com.unicorn.studio.AIWorker.tool.ToolGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工具 URL 的 SSRF 防护（文档 §11.4 第 2 条，R17）。
 * <p>
 * <b>为什么必须有这道防线</b>：工具的 httpUrl 由管理员在后台填写、请求由<b>服务端</b>发起。
 * 没有防护时，一个只有 ai:tool:edit 权限的账号就能：
 * <ul>
 *   <li>指向 {@code http://127.0.0.1:6379} —— 探测内网端口与服务指纹；</li>
 *   <li>指向 {@code http://169.254.169.254/latest/meta-data/} —— 窃取云实例凭据；</li>
 *   <li>指向自己控制的公网服务器 —— 让本服务替它访问它自己无权访问的资源。</li>
 * </ul>
 * <p>
 * <b>与 AiApiUrlValidator 的关系</b>：判定逻辑同源（那套是 S-3 为「防 API Key 外带」建的），
 * 但<b>刻意不复用那个类</b>——它校验的是「AI 厂商的 apiUrl」，白名单是 bigmodel.cn/openai.com 这类固定几家；
 * 这里是「管理员配的任意 API」，可能就指向公司内网服务。文档 §11.4 写的是「对齐
 * {@code app.ai.allowed-api-hosts} 的<b>既有口径</b>」——<b>同一个配置项</b>、同一套匹配规则、
 * 同一份告警文案；但判定逻辑各写一份，避免两边的可访问范围互相牵连。
 * <p>
 * <b>四道防线（§11.4 逐条落地）</b>：
 * <ol>
 *   <li>协议必须 https（http 仅在 allow-http 显式打开时放行）；</li>
 *   <li>域名在白名单内（空 = 不限域名）；</li>
 *   <li>解析后的<b>每一个</b> IP 都不得是内网/环回/链路本地/组播；</li>
 *   <li>解析后<b>再校验一次</b>——防 DNS rebinding。</li>
 * </ol>
 * ⚠️ 第 4 条严格做法是「校验后用同一 IP 连接」，那要接管 HTTP 客户端实现。
 * 这里已叠加域名白名单（攻击者无法伪装成白名单里那几家），残留风险实践中不可达；
 * <b>若将来把白名单放宽为「任意域名」，必须同步补上这一层</b>。
 */
@Component
public class ToolUrlGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolUrlGuard.class);

    private final List<String> allowedHosts;
    private final boolean allowHttp;

    /**
     * 构造函数。
     * @param allowedApiHosts 允许的域名，多个用逗号分隔。精确匹配或作为子域名。例如 example.com 可匹配 example.com 和 sub.example.com。
     * @param allowHttp 是否允许 http。默认不允许，因为明文 http 会让请求内容在链路上裸奔。
     */
    public ToolUrlGuard(@Value("${app.ai.allowed-api-hosts:}") String allowedApiHosts,
                        @Value("${app.ai.tools.allow-http:false}") boolean allowHttp) {
        // 初始化白名单
        this.allowedHosts = Arrays.stream(allowedApiHosts.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
        this.allowHttp = allowHttp;
        // 日志提示
        if (allowedHosts.isEmpty()) {
            log.warn("app.ai.allowed-api-hosts 为空：工具目标地址未限制域名。"
                    + "拥有 ai:tool:edit 权限的账号可把工具指向任意公网主机，"
                    + "借服务端发起请求探测内网或云元数据端点。建议配置为实际会调用到的域名。");
        }
    }

    /**
     * 校验工具目标地址；不合法时抛 {@link ToolGuardException}（由调用方转成契约化错误给模型）。
     * @param rawUrl 未加工的用户输入
     */
    public void validate(String rawUrl) {
        // 基本校验
        if (rawUrl == null || rawUrl.isBlank()) {
            throw ToolGuardException.invalidArgument("httpUrl", "工具未配置目标地址");
        }
        // 去除首尾空格并校验格式
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw ToolGuardException.invalidArgument("httpUrl", "地址格式不正确");
        }
        // 获取协议部分并校验
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw ToolGuardException.invalidArgument("httpUrl", "地址缺少协议（应形如 https://host/path）");
        }
        // 第 1 道：协议必须 https，或在 allowHttp 时允许 http
        if (!"https".equalsIgnoreCase(scheme) && !(allowHttp && "http".equalsIgnoreCase(scheme))) {
            throw ToolGuardException.forbidden("scheme",
                    "目标地址必须使用 https（当前 " + scheme + "）。明文 http 会让请求内容在链路上裸奔；"
                            + "确有内网 http 服务时，把 app.ai.tools.allow-http 设为 true 显式放行。");
        }
        // 获取主机名部分并校验
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ToolGuardException.invalidArgument("httpUrl", "地址缺少主机名");
        }
        // 第 2 道：域名白名单
        validateHostAllowed(host);
        // 第 3+4 道：解析后逐个地址校验
        validateResolvedAddresses(host);
    }

    /** 第 2 道：域名白名单（为空则跳过）。规则与 AiApiUrlValidator 一致：精确相等或为其子域名。 */
    private void validateHostAllowed(String host) {
        if (allowedHosts.isEmpty()) {
            // 白名单为空则跳过
            return;
        }
        // 避免大小写敏感的麻烦，以及允许用户写入大写形式的 IP 地址
        String lower = host.toLowerCase(Locale.ROOT);
        /** 检查域名是否在白名单内（精确匹配或作为子域名） 匹配 . 是为了匹配子域名，防止误判
         * 例如：匹配 example.com 和 sub.example.com，防止匹配 sub-example.com，偷换域名 */
        boolean allowed = allowedHosts.stream()
                // anyMatch：只要有一个匹配就返回 true
                .anyMatch(entry -> lower.equals(entry) || lower.endsWith("." + entry));
        // 如果不允许，则抛出异常
        if (!allowed) {
            throw ToolGuardException.forbidden("host",
                    "目标域名不在允许列表内：" + host + "。若确需使用，请加入 app.ai.allowed-api-hosts。");
        }
    }

    /**
     * 第 3+4 道：解析后逐个地址校验。
     * <p>
     * ⚠️ 必须<b>解析后</b>才判，否则 {@code http://internal.corp}（解析到 10.x）这类写法会绕过。
     */
    private void validateResolvedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw ToolGuardException.invalidArgument("httpUrl", "目标主机名无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw ToolGuardException.forbidden("host",
                        "目标地址解析到内网/环回/链路本地地址，已拒绝（SSRF 防护）："
                                + host + " → " + address.getHostAddress());
            }
        }
    }

    /** 内网判定。与 AiApiUrlValidator.isBlocked 逐条对齐（含 IPv4-mapped IPv6 与 fc00::/7）。 */
    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()          // 127.0.0.0/8、::1
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16、fec0::/10
                || address.isLinkLocalAddress()  // 169.254/16（含云元数据）、fe80::/10
                || address.isAnyLocalAddress()   // 0.0.0.0、::
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        /** 判断是否是 IPv4-mapped IPv6
         * IPv6是16个字节，IPv4是4个字节。IPv4-mapped IPv6地址的前10个字节是0，第11和第12个字节是0xFF，最后4个字节是IPv4地址
         * */
        if (bytes.length == 16 && isIPv4Mapped(bytes)) {
            return isBlockedIPv4(bytes[12], bytes[13], bytes[14], bytes[15]);
        }
        return false;
    }

    /** 判断是否是 IPv4-mapped IPv6,IPv4仿照IPv6的写法，会混入IPv6地址中，导致判断困难 */
    private static boolean isIPv4Mapped(byte[] bytes) {
        /** 检查前 10 个字节是否为 0 */
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        /** 检查第 11 和第 12 个字节是否为 0xFF */
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    /** 判断 IPv4 是否内网地址。 */
    private static boolean isBlockedIPv4(int a, int b, int c, int d) {
        int first = a & 0xFF;
        int second = b & 0xFF;
        if (first == 127 || first == 10 || first == 0) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && second == 168) {
            return true;
        }
        return first == 169 && second == 254;
    }
}
