package com.unicorn.studio.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-27① 的回归测试：{@code sha256} 改为复用 {@code ThreadLocal<MessageDigest>} 之后，
 * 结果必须与「每次新建 MessageDigest」完全一致。
 * <p>
 * <b>为什么这个测试是必要的</b>：{@code MessageDigest} <b>不是线程安全的</b>，
 * 复用它的前提是每次用前 {@code reset()}。一旦漏掉 reset（或在并发下共用了实例），
 * 产出的是<b>错误的哈希</b>——而 sha256 在这里用于 IP 哈希
 * （评论、PV 埋点、留言三处的 {@code ip_hash} 列）。
 * 写错不会有任何异常、不会报错，只会让统计口径悄悄失真 —— 属于「必须靠测试兜住」的一类。
 */
class IpUtilsSha256Test {

    /** 独立实现一遍作为对照（不复用被测代码的任何逻辑）。 */
    private static String expected(String input) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("单个输入的结果与标准 SHA-256 一致")
    void matchesStandardSha256() throws Exception {
        assertEquals(expected("192.168.1.1"), IpUtils.sha256("192.168.1.1"));
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                IpUtils.sha256(""),
                "空串的 SHA-256 是固定值，用于确认算法没错位");
    }

    @Test
    @DisplayName("同一线程内连续多次调用结果各自正确（验证 reset 生效）")
    void repeatedCallsOnSameThreadDoNotAccumulate() throws Exception {
        // 复用 digest 最容易出的错：忘记 reset，第二次调用的结果会受第一次影响。
        // 这里交替计算两个不同输入，确认互不污染。
        for (int i = 0; i < 5; i++) {
            assertEquals(expected("10.0.0.1"), IpUtils.sha256("10.0.0.1"), "第 " + i + " 轮 A");
            assertEquals(expected("10.0.0.2"), IpUtils.sha256("10.0.0.2"), "第 " + i + " 轮 B");
        }
    }

    @Test
    @DisplayName("多线程并发下结果仍然正确（各自持有独立实例）")
    void concurrentCallsAreCorrect() throws Exception {
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String input = "ip-" + threadId + "-" + i;
                        if (!expected(input).equals(IpUtils.sha256(input))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                assertTrue(f.get(), "并发调用下出现了错误的哈希 —— 说明 MessageDigest 被跨线程共用或未 reset");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("输出恒为 64 位小写十六进制")
    void formatIsStable() {
        String hash = IpUtils.sha256("203.0.113.7");
        assertEquals(64, hash.length(), "SHA-256 十六进制应为 64 字符（ip_hash 列是 VARCHAR(64)）");
        assertTrue(hash.matches("[0-9a-f]{64}"), "应为小写十六进制，实际：" + hash);
    }
}
