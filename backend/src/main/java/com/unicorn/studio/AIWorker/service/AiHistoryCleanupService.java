package com.unicorn.studio.AIWorker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.AIWorker.config.AiConfigProvider;
import com.unicorn.studio.AIWorker.entity.AiConversation;
import com.unicorn.studio.AIWorker.entity.AiMessage;
import com.unicorn.studio.AIWorker.mapper.AiConfigMapper;
import com.unicorn.studio.AIWorker.mapper.AiConversationMapper;
import com.unicorn.studio.AIWorker.mapper.AiMessageMapper;
import com.unicorn.studio.model.SiteSettings;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * AI 历史 90 天清理（文档 §7.4，R11）：模式复用 RetentionService —— 定时触发 +
 * {@code cleanup(dryRun, trigger)} + 把执行摘要写回 {@code site_settings} + 失败可见。
 * <p>
 * 保留天数固定 90（§7.5，不入后台可调列表），只有 cron 与单轮上限放 application.yml。
 * 摘要键是 {@code ai.cleanup.*}（§7.4），落在 ai 组；本模块统一走 {@link AiConfigMapper}，
 * 不引用 admin 侧的 {@code SiteSettingsService}（AIWorker 与其它模块的 Mapper 不互相引用）。
 * <p>
 * ⚠️ {@code ai_tool_invocation}（§11.7）建表后要一并纳入本清理，否则那张表会无限堆积。
 */
@Service
public class AiHistoryCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AiHistoryCleanupService.class);
    private static final String GROUP = AiConfigProvider.GROUP;

    private static final String KEY_LAST_RUN_AT = "ai.cleanup.last_run_at";/** 上次运行时间 **/
    private static final String KEY_LAST_RUN_TRIGGER = "ai.cleanup.last_run_trigger";/** 查看上次是手动还是定时 **/
    private static final String KEY_LAST_RUN_DELETED = "ai.cleanup.last_run_deleted";/** 删除条数 **/
    private static final String KEY_LAST_ERROR = "ai.cleanup.last_error";/** 错误信息 **/

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final AiConfigMapper configMapper;

    private final int retentionDays;/** 保留天数 **/
    private final int batchSize;/** 批量每批删除数量 **/
    private final String cron;/** 定时任务表达式 **/
    private final int maxDeletePerRun;/** 单次运行最大删除数量 **/

    // 参数上的 @Value 才是 Spring 认的；字段上写 @Value 会被 Lombok 丢掉
    public AiHistoryCleanupService(AiMessageMapper messageMapper,
                                   AiConversationMapper conversationMapper,
                                   AiConfigMapper configMapper,
                                   @Value("${app.ai.history-cleanup.retention-days:90}") int retentionDays,
                                   @Value("${app.ai.history-cleanup.batch-size:2000}") int batchSize,
                                   @Value("${app.ai.history-cleanup.cron:0 30 4 * * *}") String cron,
                                   @Value("${app.ai.history-cleanup.max-delete-per-run:50000}") int maxDeletePerRun) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.configMapper = configMapper;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.cron = cron;
        // 学长同款保护：上限至少要够一批，否则 deleteInBatches 第一轮就返回 capped
        this.maxDeletePerRun = Math.max(this.batchSize, maxDeletePerRun);
    }

    /** 获取清理状态。 */
    public CleanupStatus status(){
        Map<String, String> kv = groupValues();
        return new CleanupStatus(
                retentionDays,
                cron,
                nextRunAt(),
                parseDateTime(kv.get(KEY_LAST_RUN_AT)),
                kv.get(KEY_LAST_RUN_TRIGGER),
                parseLong(kv.get(KEY_LAST_RUN_DELETED)),
                kv.get(KEY_LAST_ERROR)
        );
    }

    /** 执行清理。 */
    public CleanupRunReport cleanup(boolean dryRun, String trigger){
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        LocalDateTime cutoff = startedAt.minusDays(retentionDays);
        long expiredMessages = countExpiredMessages(cutoff);
        long expiredConversations = countExpiredConversations(cutoff);
        // 预演模式，不实际删除数据
        if(dryRun){
            return new CleanupRunReport(trigger, true, false, "预演：将删除消息" + expiredMessages + "条，会话"
                    + expiredConversations + "个", startedAt, cutoff, expiredMessages, expiredConversations,
                    0, 0, 0, System.currentTimeMillis() - start);
        }

        long deletedMessages = 0L;
        long deletedConversations = 0L;
        try {
            BatchOutcome messages = deleteInBatches(maxDeletePerRun, limit -> messageMapper.deleteBefore(cutoff, limit)/** limit提前占位 **/);
            deletedMessages = messages.deleted;
            BatchOutcome conversations = deleteInBatches((int) Math.max(0 , maxDeletePerRun-deletedMessages)
                    , limit -> conversationMapper.deleteEmptyBefore(cutoff, limit));
            deletedConversations = conversations.deleted;
            boolean capped = messages.capped || conversations.capped;
            int batches = messages.batches + conversations.batches;
            writeSummary(startedAt, trigger, deletedMessages + deletedConversations, null);
            log.info("AI历史清理完成: trigger={} cutoff={} deleted={} batches={}",
                    trigger, cutoff, deletedMessages + deletedConversations, batches);
            String message = "已删除消息" + deletedMessages + "条，会话" + deletedConversations + "个" +
                    (capped ? "，达到单轮上限" : "");
            // 返回结果，包含删除详情
            return new CleanupRunReport(trigger , false, capped, message, startedAt, cutoff,
                    expiredMessages, expiredConversations, deletedMessages, deletedConversations, batches,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("AI历史清理失败",e);
            writeSummary(startedAt, trigger, deletedConversations+deletedMessages, e.getMessage());
            throw e;
        }
    }

    /** 每天4点30分定时清理。 */
    @Scheduled(cron = "${app.ai.history-cleanup.cron:0 30 4 * * *}")
    public void scheduledCleanup() {
        log.info("AI历史定时清理触发");
        try {
            cleanup(false, "scheduled");
        } catch (Exception e) {
            log.error("AI历史定时清理失败", e);
        }
    }

    /** 计算过期消息数。 */
    private long countExpiredMessages(LocalDateTime cutoff){
        Long count = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
                .lt(AiMessage::getCreatedAt, cutoff));
        return count == null ? 0 : count;
    }

    /** 计算过期会话数。 */
    private long countExpiredConversations(LocalDateTime cutoff){
        Long count = conversationMapper.selectCount(new LambdaQueryWrapper<AiConversation>()
                .lt(AiConversation::getLastActiveAt, cutoff)
                .notExists("SELECT 1 FROM ai_message m WHERE m.conversation_id = ai_conversation.id"));
        return count == null ? 0 : count;
    }

    /**
     * 分批物理删除直到删空或用完本轮预算（写法同 RetentionService 的 while 循环）。
     *
     * @param budget  本次允许删除的行数（两张表共用同一份，不是各给一份）
     * @param oneBatch 单批删除动作，入参为本批 LIMIT
     */
    private BatchOutcome deleteInBatches(int budget, IntFunction<Integer> oneBatch){
        long deleted = 0L;
        int batches = 0;
        while(true){
            long remainingBudget = (long)budget - deleted;// 剩余预算
            if(remainingBudget <= 0){
                // 删会话前先删消息，因此会消耗一部分预算，导致本轮预算可能未达到预算上限
                log.warn("AI历史清理达到本轮上限{}条(单轮最大上限{}条)，提前退出", budget, maxDeletePerRun);
                return new BatchOutcome(deleted, batches, true);
            }
            Integer affected = oneBatch.apply((int) Math.min(batchSize, remainingBudget));/** 批量删除行数 **/
            if(affected <= 0){
                return new BatchOutcome(deleted, batches, false);
            }
            deleted += affected;
            batches++;
        }
    }

    /** 计算下次运行时间。 */
    public LocalDateTime nextRunAt(){
        try{
            return CronExpression.parse(cron).next(LocalDateTime.now());
        }
        catch (IllegalArgumentException e){
            log.error("cleanup.cron非法，无法计算下次执行时间: {}", cron);
            return null;
        }
    }

    /** 读 ai 组的 KV。不走缓存：执行摘要必须是最新值。 */
    private Map<String, String> groupValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (SiteSettings s : configMapper.findByGroupName(GROUP)) {
            values.put(s.getSettingKey(), s.getSettingValue());
        }
        return values;
    }

    /** 写入清理执行摘要。 */
    private void writeSummary(LocalDateTime runAt, String trigger, long deleted, String error){
        try {
            configMapper.batchUpsert(List.of(
                    kv(KEY_LAST_RUN_AT, runAt.format(ISO)),
                    kv(KEY_LAST_RUN_TRIGGER, trigger),
                    kv(KEY_LAST_RUN_DELETED, Long.toString(deleted)),
                    kv(KEY_LAST_ERROR, error == null ? "" : error)
            ));
        } catch (Exception e) {
            log.warn("写入清理执行摘要失败: {}", e.getMessage());
        }
    }

    /** 快速写入KV表的方法 */
    private static SiteSettings kv(String key, String value) {
        return SiteSettings.builder().groupName(GROUP).settingKey(key).settingValue(value).build();
    }

    /** string转Long。 */
    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());/** 去除空格并转换为长整型 **/
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** string转LocalDateTime。 */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), ISO);/** 去除空格并转换为 LocalDateTime **/
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 单批删除结果：删除行数 / 批次数 / 是否被单轮上限截断 */
    private record BatchOutcome(long deleted, int batches, boolean capped) {
    }

    /** 接口 4：执行摘要（对齐 §7.4 的四个 ai.cleanup.* 键，外加定时信息）。 */
    public record CleanupStatus(int retentionDays, String cron, LocalDateTime nextRunAt,
                                LocalDateTime lastRunAt, String lastRunTrigger,
                                Long lastRunDeleted, String lastError) {
    }

    /** 单次清理报告。 */
    public record CleanupRunReport(String trigger, boolean dryRun, boolean capped, String message,
                                   LocalDateTime startedAt, LocalDateTime cutoff,
                                   long expiredMessages, long expiredConversations,
                                   long deletedMessages, long deletedConversations,
                                   int batches, long durationMs) {
    }
}
