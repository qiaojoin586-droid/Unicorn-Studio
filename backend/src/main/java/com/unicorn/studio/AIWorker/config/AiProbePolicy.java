package com.unicorn.studio.AIWorker.config;

import java.time.Duration;

/** 连通性测试的资源边界：连接时限包含 TLS 握手，请求和总时限逐层兜底。 */
public final class AiProbePolicy {
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(25);
    public static final int MAX_CONCURRENCY = 2;

    private AiProbePolicy() {
    }
}
