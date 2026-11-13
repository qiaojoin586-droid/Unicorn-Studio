package com.unicorn.studio.AIWorker.config;

import com.unicorn.studio.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * AI {@code apiUrl} 校验（S-3）：防服务端请求伪造（SSRF）与 API Key 外带。
 * <p>
 * <b>要防的是什么</b>：{@code apiUrl} 由管理员填写，最终交给 {@code OpenAiApi.Builder().baseUrl(...)}
 * 由<b>服务端</b>发起请求，且请求会带上<b>从库里取出的明文 apiKey 作为 Bearer 头</b>。
 * 一个只有 {@code ai:test} 权限（能测试、但读不到明文 key —— GET 只回脱敏值）的管理员，
 * 可以把地址指向自己的服务器，从而把库里的 key 取走；也可以拿它探内网端口、
 * 或访问云厂商元数据端点（{@code 169.254.169.254}）窃取实例凭据。
 * <p>
 * <b>两道防线，缺一不可</b>：
 * <ol>
 *   <li><b>拒绝内网/环回/链路本地地址</b> —— 挡住内网探测与云元数据。必须在<b>域名解析后</b>
 *       逐地址校验，否则 {@code http://internal.example.com} 这种「解析到 10.x」的写法会绕过；</li>
 *   <li><b>厂商域名白名单</b> —— 这一条才是挡住「API Key 外带」的关键：攻击者完全可以把自己的
 *       服务器挂在公网，第 1 条对它无效。本场景供应商就那么几家，白名单是最实用的方案。</li>
 * </ol>
 * <p>
 * ⚠️ <b>残留风险（DNS rebinding）</b>：只在「校验时」解析一次域名，理论上攻击者可以让域名先解析到
 * 公网地址（通过校验），连接时再解析到内网地址。严格的做法是校验后复用同一 IP 去连接——那需要
 * 接管 HTTP 客户端。因为这里已叠加域名白名单（攻击者无法把自己的域名伪装成 {@code *.bigmodel.cn}），
 * 该残留风险在实践中不可达。若将来放宽白名单为「任意域名」，必须同步补上这一层。
 * <p>
 * <b>落点要求</b>：探测（{@code test()}）与保存（{@code save()}）<b>两条路径都必须校验</b> ——
 * 否则可以「先存一个内网地址，再触发对话」绕开探测路径的检查。
 */
@Component
public class AiApiUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(AiApiUrlValidator.class);

    /** 生产环境必须走 https：明文 http 会让 API Key 在链路上裸奔 */
    private static final String REQUIRED_SCHEME = "https";

    /** 厂商域名白名单（逗号分隔，空 = 不限制域名，仅保留内网地址拦截） */
    private final List<String> allowedHosts;
    private final HostResolver resolver;

    @Autowired
    public AiApiUrlValidator(@Value("${app.ai.allowed-api-hosts:}") String allowedApiHosts) {
        this(allowedApiHosts, InetAddress::getAllByName);
    }

    AiApiUrlValidator(String allowedApiHosts, HostResolver resolver) {
        this.resolver = resolver;
        this.allowedHosts = Arrays.stream(allowedApiHosts.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
        if (allowedHosts.isEmpty()) {
            log.warn("app.ai.allowed-api-hosts 为空：AI 接口地址未限制域名。"
                    + "此时拥有 ai:test 权限的账号可把 apiUrl 指向任意公网主机，从而取走库中的明文 API Key。"
                    + "建议配置为实际使用的厂商域名（如 bigmodel.cn,openai.com,deepseek.com）。");
        } else {
            log.info("AI 接口地址白名单已启用：{}", allowedHosts);
        }
    }

    /**
     * 校验 AI 接口地址；不合法时抛 {@link BusinessException}（400），由调用方转成可读提示。
     *
     * @param rawUrl 用户提交的地址；为空时直接返回（「未填写」由既有逻辑处理）
     */
    public void validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }
        String url = rawUrl.trim();

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw BusinessException.badRequest("API 地址格式不正确：" + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !REQUIRED_SCHEME.equalsIgnoreCase(scheme)) {
            throw BusinessException.badRequest(
                    "API 地址必须以 https:// 开头（当前为 " + (scheme == null ? "无协议" : scheme + "://")
                            + "）。明文 http 会让 API Key 在链路上以明文传输。");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw BusinessException.badRequest("API 地址缺少主机名：" + url);
        }

        validateHostAllowed(host);
        validateResolvedAddresses(host);
    }

    /** 第 2 道防线：域名必须在白名单内（白名单为空时跳过）。 */
    private void validateHostAllowed(String host) {
        if (allowedHosts.isEmpty()) {
            return;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts.stream()
                // 精确相等 或 子域名（example.com 放行 api.example.com，但不放行 notexample.com）
                .anyMatch(entry -> lowerHost.equals(entry) || lowerHost.endsWith("." + entry));
        if (!allowed) {
            throw BusinessException.badRequest("API 地址的域名不在允许列表内：" + host
                    + "。若确需使用该厂商，请把它加入配置项 app.ai.allowed-api-hosts（逗号分隔），"
                    + "当前允许：" + String.join(",", allowedHosts));
        }
    }

    /** 第 1 道防线：域名解析后的<b>每一个</b>地址都不得是内网/环回/链路本地/组播地址。 */
    private void validateResolvedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw BusinessException.badRequest("API 地址的主机名无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw BusinessException.badRequest("API 地址解析到内网/环回/链路本地地址，已拒绝（SSRF 防护）："
                        + host + " → " + address.getHostAddress());
            }
        }
    }

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isLoopbackAddress()          // 127.0.0.0/8、::1
                || address.isSiteLocalAddress()  // 10/8、172.16/12、192.168/16、fec0::/10
                || address.isLinkLocalAddress()  // 169.254/16（含云元数据 169.254.169.254）、fe80::/10
                || address.isAnyLocalAddress()   // 0.0.0.0、::
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 唯一本地地址 fc00::/7 —— Java 的 isSiteLocalAddress() 不覆盖这一段
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        // IPv4-mapped IPv6（::ffff:10.0.0.1）：按内嵌的 IPv4 再判一次，防「用 v6 写法绕过」
        if (bytes.length == 16 && isIPv4Mapped(bytes)) {
            return isBlockedIPv4(bytes[12], bytes[13], bytes[14], bytes[15]);
        }
        return false;
    }

    private static boolean isIPv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

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
        // 169.254.0.0/16 链路本地（含 169.254.169.254 云元数据端点）
        return first == 169 && second == 254;
    }
}
