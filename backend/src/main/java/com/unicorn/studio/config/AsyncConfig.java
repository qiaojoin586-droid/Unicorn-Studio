package com.unicorn.studio.config;

import com.unicorn.studio.AIWorker.config.AiProbePolicy;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时任务基础。
 * <p>
 * <b>为什么按职责拆成多个池（B-04）</b>：原先只有一个 {@code asyncExecutor} 同时服务
 * 「PV 埋点」（每次浏览一条 INSERT，高频、丢了无所谓）与「审计落库」（后台操作，低频但绝不能丢）。
 * 两者恰是「可以丢」与「绝不能丢」的两个极端，共用一个队列的结果是：<b>高频的埋点把队列灌满后，
 * 审计日志被一起拒绝</b>。
 * <p>
 * <b>每个池都显式设置拒绝策略</b>：不设就会用默认的 {@code AbortPolicy}（抛异常）。
 * 而审计的提交点在 {@code AuditLogAspect} 的 <b>{@code finally} 块</b>里 ——
 * 从 finally 抛出的异常会<b>取代 try 中的正常返回值</b>，于是「业务已成功落库，前端却收到 500」，
 * 且原始异常会被提交异常顶掉，错误日志里看到的是 TaskRejectedException 而非真正的故障原因。
 * <p>
 * <b>并发语义说明</b>：{@code ThreadPoolTaskExecutor}（底层 ThreadPoolExecutor）的排队规则是
 * 「核心线程满 → <b>全部进队列</b> → 队列满了才扩容到 max」。因此队列容量给大时，
 * 实际并发度≈corePoolSize，max 形同虚设；容量与 max 必须一起看。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** 外部 AI 探测单独限流：零队列，满载立即反馈，禁止回退到 Tomcat 线程执行。 */
    @Bean("aiProbeExecutor")
    public ThreadPoolTaskExecutor aiProbeExecutor() {
        var pool = buildPool("ai-probe-", AiProbePolicy.MAX_CONCURRENCY, AiProbePolicy.MAX_CONCURRENCY,
                0, new ThreadPoolExecutor.AbortPolicy(), 5);
        pool.setAllowCoreThreadTimeOut(true);
        pool.setWaitForTasksToCompleteOnShutdown(false);
        return pool;
    }

    /**
     * PV 埋点等「弱一致、可丢弃」任务。
     * <p>
     * {@link ThreadPoolExecutor.CallerRunsPolicy}：队列满时由<b>调用线程</b>兜底执行，
     * 把「系统过载」转化为「请求变慢」而不是「数据丢失 + 抛异常」。
     * 这里刻意不用 DiscardPolicy —— 丢埋点是可接受的，但「静默丢弃」比「慢一点」更难察觉。
     */
    @Bean("statsExecutor")
    public Executor statsExecutor() {
        return buildPool("stats-", 2, 2, 500, new ThreadPoolExecutor.CallerRunsPolicy(), 10);
    }

    /**
     * 审计日志落库：绝不能静默丢弃。
     * <p>
     * 同样用 CallerRunsPolicy —— 让请求线程承担落库（可观测、不丢数据），
     * 而不是抛异常覆盖业务返回值。core=1 是因为审计写入本身很快（单条 INSERT），
     * max=2 只用于「队列满 + 突发」时的扩容。
     */
    @Bean("auditExecutor")
    public Executor auditExecutor() {
        return buildPool("audit-", 1, 2, 500, new ThreadPoolExecutor.CallerRunsPolicy(), 10);
    }

    /**
     * 缓存预热：启动时一次性执行，之后长期空闲。
     * <p>
     * 独立成池而不是复用 statsExecutor：预热是一段较长的连续工作（要填多个 key），
     * 与它共享线程会让启动后最初的一批 PV 埋点排队。单线程足够，代价可忽略。
     */
    @Bean("warmupExecutor")
    public Executor warmupExecutor() {
        return buildPool("warmup-", 1, 1, 10, new ThreadPoolExecutor.CallerRunsPolicy(), 30);
    }

    /**
     * 邮件通知（SMTP，外部网络 IO，数百毫秒到数秒）。
     * <p>
     * 独立成池且用<b>丢弃并告警</b>策略：邮件是「锦上添花」的通知，
     * 队列满时绝不能像埋点那样用 CallerRuns 兜底 —— 那会把请求线程卡在一次 SMTP 投递上，
     * 正是本项要消除的问题。丢弃时记 WARN 保证可观测，而不是静默消失。
     */
    @Bean("mailExecutor")
    public Executor mailExecutor() {
        return buildPool("mail-", 1, 1, 100, (task, executor) ->
                LoggerFactory.getLogger(AsyncConfig.class)
                        .warn("邮件队列已满，本次通知被丢弃（不影响留言回复本身的成功）"), 10);
    }

    /**
     * 备份任务（mysqldump，分钟级慢 IO）。
     * <p>
     * <b>刻意保留 AbortPolicy</b>：备份绝不能像埋点那样用 CallerRuns 兜底 ——
     * 那会让一个 HTTP 请求线程去跑几分钟的 mysqldump。提交被拒时应快速失败，
     * 并由 {@code BackupService.trigger} 捕获后把那条记录置为 failed（否则会留下永久
     * {@code running} 的僵尸记录，后台备份列表里出现一条永不结束的备份）。
     */
    @Bean("backupExecutor")
    public Executor backupExecutor() {
        return buildPool("backup-", 1, 1, 10, new ThreadPoolExecutor.AbortPolicy(), 30);
    }

    private static ThreadPoolTaskExecutor buildPool(String threadNamePrefix,
                                                    int corePoolSize,
                                                    int maxPoolSize,
                                                    int queueCapacity,
                                                    RejectedExecutionHandler rejectionHandler,
                                                    int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(corePoolSize);
        pool.setMaxPoolSize(maxPoolSize);
        pool.setQueueCapacity(queueCapacity);
        pool.setThreadNamePrefix(threadNamePrefix);
        pool.setRejectedExecutionHandler(rejectionHandler);
        // 关闭时等在途任务跑完，避免丢弃「已提交但未落库」的审计日志。
        // 注意不要手工调用 pool.initialize()：父类 ExecutorConfigurationSupport 实现了
        // InitializingBean，afterPropertiesSet() 就是调 initialize()；手工调用会先建一个
        // ExecutorService 再被覆盖丢弃（B-30①）。
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(awaitTerminationSeconds);
        return pool;
    }
}
