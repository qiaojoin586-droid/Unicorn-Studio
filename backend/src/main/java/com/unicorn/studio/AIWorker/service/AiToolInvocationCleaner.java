package com.unicorn.studio.AIWorker.service;

import com.unicorn.studio.AIWorker.mapper.AiToolInvocationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 工具调用审计记录的保留期清理（§7.4 / §11.7）。
 * <p>
 * <b>为什么单独一个类</b>：{@code ai_tool_invocation} 是「每次工具调用一行」的表，
 * 随使用量线性增长，而它没有别的清理入口 ⇒ 不清就会无限堆积。
 * 文档 §7.4 推荐的落点是「纳入 RetentionService 的清理项」，
 * 但 RetentionService 在 {@code admin} 包 ⇒ 让它直接依赖 AIWorker 的 mapper 会跨模块耦合，
 * 所以清理动作留在 AIWorker 侧，由 RetentionService 调用。
 */
@Service
public class AiToolInvocationCleaner {

    private static final Logger log = LoggerFactory.getLogger(AiToolInvocationCleaner.class);

    private final AiToolInvocationMapper mapper;

    public AiToolInvocationCleaner(AiToolInvocationMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分批删除保留期之前的调用记录。
     * <p>
     * ⚠️ <b>为什么要分批</b>：一条 DELETE 会锁住整张表；分批把锁范围和单次事务体积都压住，
     * 跟审计日志的清理是同一套做法。
     * <p>
     * ⚠️ <b>为什么要设单轮上限</b>：表大了以后一次删光会让事务日志暴涨、
     * 挤占其它任务的连接（审计日志清理那边也是这个考虑）。
     *
     * @param cutoff   保留截止时间，严格删除 {@code created_at < cutoff} 的行
     * @param batchSize 单批行数
     * @param maxTotal  本轮最多删多少行
     * @return 删除结果（实际删了多少行、是否撞到上限）
     */
    public CleanupOutcome deleteExpired(LocalDateTime cutoff, int batchSize, int maxTotal) {
        long deleted = 0L;
        int batches = 0;
        boolean capped = false;
        while (true) {
            long remaining = (long) maxTotal - deleted;
            if (remaining <= 0) {
                capped = true;
                break;
            }
            int limit = (int) Math.min(batchSize, remaining);
            int affected = mapper.deleteBefore(cutoff, limit);
            if (affected <= 0) {
                break;
            }
            deleted += affected;
            batches++;
        }
        if (deleted > 0) {
            log.info("工具调用审计清理：删除 {} 行（{} 批，cutoff={}）", deleted, batches, cutoff);
        }
        return new CleanupOutcome(deleted, batches, capped);
    }

    /**
     * 清理结果。
     *
     * @param deleted 实际删除行数
     * @param batches 删除批数
     * @param capped  是否撞到单轮上限（撞到 ⇒ 还有数据留到下次）
     */
    public record CleanupOutcome(long deleted, int batches, boolean capped) {
    }
}
