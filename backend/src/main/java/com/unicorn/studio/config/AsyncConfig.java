package com.unicorn.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步与定时任务基础：
 * - asyncExecutor：审计日志/埋点写入（不拖慢业务响应）
 * - backupExecutor：备份任务（慢 IO，独立池防阻塞）
 * - @EnableScheduling：聚合统计与定时备份
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(8);
        pool.setQueueCapacity(200);
        pool.setThreadNamePrefix("async-");
        pool.initialize();
        return pool;
    }

    @Bean("backupExecutor")
    public Executor backupExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(10);
        pool.setThreadNamePrefix("backup-");
        pool.initialize();
        return pool;
    }
}
