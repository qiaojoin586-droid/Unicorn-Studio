package com.unicorn.studio.AIWorker.config;

import com.unicorn.studio.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-3（AI apiUrl SSRF 防护）的回归测试。
 * <p>
 * 之所以值得单独写测试：这是一条**安全边界**，而它的失败方式是「静默放行」——
 * 校验被误删或被改宽时不会有任何运行时报错，只会在某天被人把库里的明文 API Key 取走。
 * 用例覆盖两道防线（内网地址拦截 / 域名白名单）与几个典型绕过写法。
 */
class AiApiUrlValidatorTest {

    /** 模拟生产配置：只允许常见厂商域名 */
    private final AiApiUrlValidator withAllowlist = new AiApiUrlValidator(
            "bigmodel.cn,openai.com,deepseek.com");

    /** 模拟未配白名单：只剩内网地址拦截 */
    private final AiApiUrlValidator noAllowlist = new AiApiUrlValidator("");

    // ---------- 应放行 ----------

    @Test
    @DisplayName("白名单内的厂商域名放行")
    void allowsWhitelistedVendor() {
        assertDoesNotThrow(() -> withAllowlist.validate("https://open.bigmodel.cn/api/paas/v4"));
        assertDoesNotThrow(() -> withAllowlist.validate("https://api.deepseek.com/v1"));
        // 白名单域名的子域名也应放行
        assertDoesNotThrow(() -> withAllowlist.validate("https://api.openai.com/v1"));
    }

    @Test
    @DisplayName("空地址放行（「未填写」由既有逻辑处理，不归校验器管）")
    void allowsBlank() {
        assertDoesNotThrow(() -> withAllowlist.validate(null));
        assertDoesNotThrow(() -> withAllowlist.validate("   "));
    }

    // ---------- 第一道防线：协议 / 内网地址 ----------

    @Test
    @DisplayName("拒绝明文 http（API Key 会在链路上裸奔）")
    void rejectsPlainHttp() {
        assertThrows(BusinessException.class,
                () -> noAllowlist.validate("http://api.deepseek.com/v1"));
    }

    @Test
    @DisplayName("拒绝环回地址（含 127.0.0.1 与用十六进制/省略写法伪装的形态）")
    void rejectsLoopback() {
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://127.0.0.1:8080/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://127.1/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://[::1]/v1"));
    }

    @Test
    @DisplayName("拒绝云元数据端点 169.254.169.254（可窃取实例凭据）")
    void rejectsCloudMetadata() {
        assertThrows(BusinessException.class,
                () -> noAllowlist.validate("https://169.254.169.254/latest/meta-data/"));
    }

    @Test
    @DisplayName("拒绝 RFC1918 私网段")
    void rejectsPrivateRanges() {
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://10.0.0.5/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://172.16.3.4/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://192.168.1.1/v1"));
    }

    @Test
    @DisplayName("拒绝 IPv6 唯一本地地址 fc00::/7 与 IPv4-mapped 写法")
    void rejectsIPv6Tricks() {
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://[fc00::1]/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://[fd12:3456::1]/v1"));
        // ::ffff:10.0.0.1 —— 用 v6 写法包装私网 v4，不能绕过
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://[::ffff:10.0.0.1]/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://[::ffff:127.0.0.1]/v1"));
    }

    // ---------- 第二道防线：域名白名单 ----------

    @Test
    @DisplayName("白名单外的公网域名被拒（这是防 API Key 外带的关键一条）")
    void rejectsHostOutsideAllowlist() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> withAllowlist.validate("https://evil-attacker.example.com/v1"));
        // 报错必须告诉运维怎么加白名单，否则会变成「不知道为什么存不进去」
        assertTrue(e.getMessage().contains("allowed-api-hosts"),
                "拒绝信息应包含配置项名称以便自助修复，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("后缀相似但非子域名的域名不能被放行（notbigmodel.cn 不是 bigmodel.cn 的子域名）")
    void doesNotAllowLookalikeDomain() {
        assertThrows(BusinessException.class,
                () -> withAllowlist.validate("https://notbigmodel.cn/v1"));
        assertThrows(BusinessException.class,
                () -> withAllowlist.validate("https://evilbigmodel.cn.attacker.com/v1"));
    }

    @Test
    @DisplayName("未配白名单时不限制域名，但内网拦截仍然生效")
    void allowlistDisabledStillBlocksPrivate() {
        // 用 example.com（RFC 2606 保留域，可解析到公网地址）验证「非白名单域名被放行」；
        // 注意不能用 some-vendor.example.com 这类不存在的子域——那会被「解析失败」拦下，测的就不是白名单了
        assertDoesNotThrow(() -> noAllowlist.validate("https://example.com/v1"));
        assertThrows(BusinessException.class, () -> noAllowlist.validate("https://10.0.0.5/v1"));
    }

    @Test
    @DisplayName("主机名无法解析时拒绝（避免「校验时解析失败、连接时却通了」的窗口）")
    void rejectsUnresolvableHost() {
        assertThrows(BusinessException.class,
                () -> noAllowlist.validate("https://this-host-does-not-exist-unicorn-test.invalid/v1"));
    }
}
