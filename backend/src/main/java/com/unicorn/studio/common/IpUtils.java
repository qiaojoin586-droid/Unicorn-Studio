package com.unicorn.studio.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 提取（全站唯一实现，限流 / 审计 / 埋点共用）。
 * <p>
 * <b>信任边界</b>：{@code X-Forwarded-For} 是客户端可伪造的请求头。只有应用<b>必然</b>经过
 * 可信代理时才可采信，且必须<b>从右往左</b>取第 N 跳（N = 可信代理数量）：
 * <pre>
 * 客户端伪造 XFF: 1.2.3.4  →  nginx（$proxy_add_x_forwarded_for，追加语义）
 * 后端收到      : "1.2.3.4, &lt;真实客户端IP&gt;"
 *                 ↑ 最左 = 攻击者可控      ↑ 最右 = 可信代理写入的真实值
 * </pre>
 * 旧实现取<b>最左</b>值，等于直接采信攻击者提供的字符串 —— 8 个 IP 维度限流全部可被
 * 一条 {@code X-Forwarded-For: <随机IP>} 请求头绕过（S-7）。
 * <p>
 * {@code trustedProxyCount} 默认 <b>0</b>（完全不信任 XFF，只用 {@code remoteAddr}）——
 * 这是安全默认值，由 {@link com.unicorn.studio.config.IpUtilsConfig} 在启动时按
 * {@code app.security.trusted-proxy-count} 注入。
 * <p>
 * <b>部署提醒</b>：本应用 {@code server.address=127.0.0.1} 只监听回环，生产必经代理，
 * 因此该值必须配成<b>实际代理跳数</b>。配错（尤其配小）会让全站共用一个限流桶：
 * 取到的是代理自身 IP，而非真实客户端。
 */
public final class IpUtils {

    private IpUtils() {
    }

    /**
     * 可信代理跳数；0 = 完全不信任 X-Forwarded-For。
     * 启动时写入一次（见 IpUtilsConfig），之后只读，故用 volatile 保证可见性。
     */
    private static volatile int trustedProxyCount = 0;

    /**
     * 由 {@link com.unicorn.studio.config.IpUtilsConfig} 在启动时注入。
     * <p>
     * 之所以用静态注入而非构造器注入：本工具类是全站静态入口（限流/审计/评论/埋点/留言共 6 处调用），
     * 改造成注入会把一个部署级常量扩散进每个调用方，反而更容易出现「某处漏改口径不一致」。
     */
    public static void configureTrustedProxyCount(int count) {
        trustedProxyCount = Math.max(0, count);
    }

    /** 当前生效的可信代理跳数（供启动日志与诊断使用）。 */
    public static int getTrustedProxyCount() {
        return trustedProxyCount;
    }

    public static String getClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();

        int hops = trustedProxyCount;
        if (hops <= 0) {
            // 安全默认值：不信任任何客户端可伪造的头
            return remote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }

        String[] parts = forwarded.split(",");
        int index = parts.length - hops;
        if (index < 0) {
            // 头里的跳数少于配置的可信代理数：请求未经预期路径进来，保守回退到直连地址
            return remote;
        }

        String candidate = parts[index].trim();
        return candidate.isEmpty() ? remote : candidate;
    }

    public static String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
