package com.unicorn.studio.admin.service;

import com.alibaba.druid.stat.DruidStatManagerFacade;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SysOperationLogMapper;
import com.unicorn.studio.model.SysOperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 数据保留清理策略（V14）。
 * <p>
 * 「审计日志」「SQL 监控」数据仅保留最近 N 天，N 由后台「数据保留」页面配置（默认 7，可选 7/15/30/90）。
 * 三类清理对象：
 * <ol>
 *   <li><b>审计日志（DB）</b>：{@code sys_operation_log} 中 {@code created_at < now - N 天} 的行，
 *       分批物理删除（命中 idx_operation_log_time）；</li>
 *   <li><b>SQL 监控（内存）</b>：Druid 连接池统计为 JVM 内存聚合，无逐条时间戳，无法按记录裁剪；
 *       采用「距上次重置满 N 天则整体 {@code resetAll()}」的方式，保证统计窗口不超过保留期
 *       （重置时间落库 {@code retention.sql_monitor_last_reset}，重启不丢）；</li>
 *   <li><b>清理操作自身产生的日志</b>：清理任务本身是 {@code @AuditLog} 写接口，其产生的
 *       ① DB 审计行会被下一次清理按同一规则删除；② 管理端审计文件（{@code logs/admin/yyyy-MM-dd/}）
 *       由本任务按同一保留期裁剪整个日期目录 —— 即「清理日志也被定期清理」，不会自我堆积。
 *       注意：审计文件另受 logback {@code maxHistory=30} 硬上限约束，故保留期设为 90 天时
 *       文件侧实际仍为 30 天；数据库中的审计日志不受此限制。</li>
 * </ol>
 * 配置存储：复用 {@code site_settings} 的 {@code retention} 组，键 {@code retention.days} /
 * {@code retention.enabled}；每次清理都实时读取，改配置无需重启即生效。
 * <p>
 * 触发方式：① 定时（{@code app.retention.cron}，默认每日 03:30，错开 01:00/02:00/03:00 的既有任务）；
 * ② 后台手动「立即清理」（先 dry-run 预览，再确认执行）。
 * <p>
 * 安全性与可恢复性：支持 dry-run 预览、单轮删除行数上限、单批 LIMIT 分批；删除为物理删除不可从库内回滚，
 * 恢复依赖既有的每日 mysqldump 备份（{@code backup_record}，默认保留 7 份），因此建议保留期不短于备份保留份数。
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** site_settings 分组名 */
    public static final String GROUP = "retention";

    private static final String KEY_DAYS = "retention.days";
    private static final String KEY_ENABLED = "retention.enabled";
    /** 上次 SQL 监控重置时间（内存统计无逐条时间戳，需记录重置基准以按保留期节流） */
    private static final String KEY_SQL_LAST_RESET = "retention.sql_monitor_last_reset";
    /** 上次清理摘要（落库以便重启后仍可观测） */
    private static final String KEY_LAST_RUN_AT = "retention.last_run_at";
    private static final String KEY_LAST_RUN_TRIGGER = "retention.last_run_trigger";
    private static final String KEY_LAST_RUN_DELETED = "retention.last_run_deleted";

    /** 默认保留期（天） */
    public static final int DEFAULT_DAYS = 7;
    /** 可选档位（有序，后台下拉按此展示） */
    public static final List<Integer> ALLOWED_DAYS_ORDERED = List.of(7, 15, 30, 90);
    /** 可选档位集合，越界值一律回落到默认档 */
    public static final Set<Integer> ALLOWED_DAYS = Set.copyOf(ALLOWED_DAYS_ORDERED);

    /** 单批删除行数：控制单条 DELETE 的锁范围 */
    private static final int BATCH_SIZE = 2000;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 审计文件目录名严格为 yyyy-MM-dd，只裁剪命中该格式的目录 */
    private static final Pattern DATE_DIR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final SysOperationLogMapper logMapper;
    private final SiteSettingsService settingsService;
    private final DruidStatManagerFacade statManager = DruidStatManagerFacade.getInstance();

    private final String cron;
    private final int maxDeletePerRun;
    private final Path logRoot;

    public RetentionService(SysOperationLogMapper logMapper,
                           SiteSettingsService settingsService,
                           @Value("${app.retention.cron:0 30 3 * * *}") String cron,
                           @Value("${app.retention.max-delete-per-run:50000}") int maxDeletePerRun,
                           @Value("${logging.file.path:./logs}") String logPath) {
        this.logMapper = logMapper;
        this.settingsService = settingsService;
        this.cron = cron;
        this.maxDeletePerRun = Math.max(BATCH_SIZE, maxDeletePerRun);
        // 审计文件目录：${logging.file.path}/admin/yyyy-MM-dd/app-admin.log.N（%i 文件名索引，见 logback-spring.xml）
        this.logRoot = Path.of(logPath).resolve("admin").toAbsolutePath().normalize();
    }

    // =====================================================================
    // 配置读写
    // =====================================================================

    /** 读取当前生效的保留策略（含默认值兜底）。 */
    public RetentionConfig loadConfig() {
        return loadConfig(settingsService.listGroup(GROUP));
    }

    /** 基于已加载的 KV 构造配置，供同一请求内已读过 group 的调用方复用，避免二次查库。 */
    private RetentionConfig loadConfig(Map<String, String> kv) {
        int days = normalizeDays(kv.get(KEY_DAYS));
        boolean enabled = parseBoolean(kv.get(KEY_ENABLED), true);
        return new RetentionConfig(days, enabled, cron, maxDeletePerRun, new ArrayList<>(ALLOWED_DAYS_ORDERED));
    }

    /** 保存保留策略：档位必须为 7/15/30/90，否则 400（不做静默兜底，避免管理员误以为已生效）。 */
    @Transactional
    public void saveConfig(Integer days, Boolean enabled) {
        int normalized = requireAllowedDays(days);
        boolean enabledValue = enabled == null || enabled;
        settingsService.updateAll(List.of(
                kv(KEY_DAYS, Integer.toString(normalized)),
                kv(KEY_ENABLED, Boolean.toString(enabledValue))));
        log.info("数据保留策略已更新: days={}, enabled={}", normalized, enabledValue);
    }

    /** 状态总览：配置 + 上次执行摘要 + 下次执行时间 + 上次 SQL 监控重置时间（重启后仍可读）。 */
    public RetentionStatus status() {
        Map<String, String> kv = settingsService.listGroup(GROUP);
        RetentionRunReport lastRun = null;
        LocalDateTime lastRunAt = parseDateTime(kv.get(KEY_LAST_RUN_AT));
        if (lastRunAt != null) {
            lastRun = new RetentionRunReport(
                    kv.getOrDefault(KEY_LAST_RUN_TRIGGER, "unknown"),
                    false, false, "历史执行记录",
                    lastRunAt, lastRunAt,
                    normalizeDays(kv.get(KEY_DAYS)),
                    lastRunAt.minusDays(normalizeDays(kv.get(KEY_DAYS))),
                    parseLong(kv.get(KEY_LAST_RUN_DELETED)),
                    0, false, false, 0, 0L);
        }
        return new RetentionStatus(loadConfig(kv), lastRun, nextRunAt(), parseDateTime(kv.get(KEY_SQL_LAST_RESET)));
    }

    /** 下次定时执行时间（cron 非法时返回 null，不影响页面渲染）。 */
    public LocalDateTime nextRunAt() {
        try {
            return CronExpression.parse(cron).next(LocalDateTime.now());
        } catch (IllegalArgumentException e) {
            log.warn("app.retention.cron 非法，无法计算下次执行时间: {}", cron);
            return null;
        }
    }

    // =====================================================================
    // 预览 / 清理
    // =====================================================================

    /** 预览：不产生任何副作用，供后台「立即清理」二次确认前展示。 */
    public RetentionPreview preview() {
        int days = loadConfig().days();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        long total = countAuditRows(null);
        long expired = countAuditRows(cutoff);

        List<Path> dirs = listExpiredLogDirs(cutoff.toLocalDate());
        long bytes = 0L;
        for (Path dir : dirs) {
            bytes += directorySize(dir);
        }
        return new RetentionPreview(cutoff, total, expired, dirs.size(), bytes);
    }

    /**
     * 执行清理（或 dry-run）。
     * <p>
     * enabled=false 时：定时触发直接跳过；手动触发仍然执行（管理员显式操作优先），但报告会标注 disabled。
     *
     * @param dryRun          true=只统计不删除
     * @param trigger         scheduled / manual
     * @param forceSqlMonitor 手动触发时可强制重置 SQL 监控统计（不受「满 N 天」节流限制）
     */
    public RetentionRunReport cleanup(boolean dryRun, String trigger, boolean forceSqlMonitor) {
        long start = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now();
        RetentionConfig cfg = loadConfig();

        boolean manual = "manual".equals(trigger);
        if (!cfg.enabled() && !manual) {
            return new RetentionRunReport(trigger, dryRun, true, "自动清理已关闭，跳过本次执行",
                    startedAt, LocalDateTime.now(), cfg.days(), startedAt.minusDays(cfg.days()),
                    0, 0, false, false, 0, System.currentTimeMillis() - start);
        }

        LocalDateTime cutoff = startedAt.minusDays(cfg.days());
        long expired = countAuditRows(cutoff);

        if (dryRun) {
            List<Path> dirs = listExpiredLogDirs(cutoff.toLocalDate());
            long bytes = 0L;
            for (Path dir : dirs) {
                bytes += directorySize(dir);
            }
            String msg = "预演：将删除审计日志 " + expired + " 条、裁剪审计文件目录 " + dirs.size()
                    + " 个（约 " + humanSize(bytes) + "）";
            return new RetentionRunReport(trigger, true, false, msg, startedAt, LocalDateTime.now(),
                    cfg.days(), cutoff, 0, 0, expired > maxDeletePerRun, false, 0, System.currentTimeMillis() - start);
        }

        // ---- 1. 审计日志（DB）：分批物理删除 ----
        long deleted = 0L;
        int batches = 0;
        boolean capped = false;
        while (true) {
            long remainingBudget = (long) maxDeletePerRun - deleted;
            if (remainingBudget <= 0) {
                capped = true;
                break;
            }
            int limit = (int) Math.min(BATCH_SIZE, remainingBudget);
            int affected = logMapper.deleteBefore(cutoff, limit);
            if (affected <= 0) {
                break;
            }
            deleted += affected;
            batches++;
        }
        if (capped) {
            log.warn("审计日志清理达到单轮上限 {} 条，剩余数据待下次清理", maxDeletePerRun);
        }

        // ---- 2. SQL 监控（内存）：按保留期节流整体重置 ----
        boolean sqlReset = false;
        LocalDateTime lastReset = parseDateTime(settingsService.getValue(KEY_SQL_LAST_RESET));
        boolean due = lastReset == null
                || Duration.between(lastReset, startedAt).toDays() >= cfg.days();
        if (forceSqlMonitor || due) {
            try {
                statManager.resetAll();
                sqlReset = true;
                settingsService.updateAll(List.of(kv(KEY_SQL_LAST_RESET, ISO.format(startedAt))));
            } catch (Exception e) {
                log.warn("SQL 监控统计重置失败: {}", e.getMessage());
            }
        }

        // ---- 3. 审计文件：裁剪超过保留期的日期目录 ----
        int deletedDirs = pruneAuditLogDirs(cutoff.toLocalDate());

        // ---- 4. 落库本次执行摘要（供重启后观测；该行本身会在后续清理中被删除） ----
        try {
            settingsService.updateAll(List.of(
                    kv(KEY_LAST_RUN_AT, ISO.format(startedAt)),
                    kv(KEY_LAST_RUN_TRIGGER, trigger),
                    kv(KEY_LAST_RUN_DELETED, Long.toString(deleted))));
        } catch (Exception e) {
            log.warn("写入清理执行摘要失败: {}", e.getMessage());
        }

        String message = "已删除审计日志 " + deleted + " 条（" + batches + " 批）"
                + (capped ? "（达单轮上限，剩余待下次）" : "")
                + "，SQL 监控统计" + (sqlReset ? "已重置" : "未到重置周期")
                + "，裁剪审计文件目录 " + deletedDirs + " 个";
        log.info("数据保留清理完成: trigger={} cutoff={} deleted={} batches={} sqlReset={} dirs={}",
                trigger, cutoff, deleted, batches, sqlReset, deletedDirs);

        return new RetentionRunReport(trigger, false, false, message, startedAt, LocalDateTime.now(),
                cfg.days(), cutoff, deleted, batches, capped, sqlReset, deletedDirs,
                System.currentTimeMillis() - start);
    }

    /** 定时清理：默认每日 03:30，错开既有 01:00 / 02:00 / 03:00 定时任务。 */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        log.info("数据保留定时清理触发");
        try {
            RetentionRunReport report = cleanup(false, "scheduled", false);
            if (report.skipped()) {
                log.info("数据保留定时清理跳过: {}", report.message());
            }
        } catch (Exception e) {
            // 定时任务异常必须自行兜住，否则线程池会持续打印堆栈
            log.error("数据保留定时清理失败", e);
        }
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private long countAuditRows(LocalDateTime cutoff) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (cutoff != null) {
            wrapper.lt(SysOperationLog::getCreatedAt, cutoff);
        }
        Long count = logMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    /** 列出所有「日期早于保留截止日」的管理端审计目录。 */
    private List<Path> listExpiredLogDirs(LocalDate cutoffDate) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(logRoot)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(logRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (!DATE_DIR.matcher(name).matches()) {
                    continue;
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(name);
                } catch (DateTimeParseException e) {
                    continue;
                }
                if (date.isBefore(cutoffDate)) {
                    result.add(dir);
                }
            }
        } catch (IOException e) {
            log.warn("扫描审计日志目录失败: {}", e.getMessage());
        }
        return result;
    }

    /** 删除超过保留期的审计日期目录（含目录内文件），返回成功删除的目录数。 */
    private int pruneAuditLogDirs(LocalDate cutoffDate) {
        int deleted = 0;
        for (Path dir : listExpiredLogDirs(cutoffDate)) {
            // 二次保险：解析后的绝对路径必须仍在审计目录之下
            Path target = dir.toAbsolutePath().normalize();
            if (!target.startsWith(logRoot) || target.equals(logRoot)) {
                continue;
            }
            if (deleteRecursively(target)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            // 逆序：先文件后目录
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            log.warn("删除审计日志目录失败 {}: {}", root, e.getMessage());
            return false;
        }
    }

    private static long directorySize(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static int normalizeDays(String value) {
        Integer parsed = tryParseInt(value);
        return parsed != null && ALLOWED_DAYS.contains(parsed) ? parsed : DEFAULT_DAYS;
    }

    private static int requireAllowedDays(Integer days) {
        if (days == null || !ALLOWED_DAYS.contains(days)) {
            throw BusinessException.badRequest("保留期限只能是 " + ALLOWED_DAYS_ORDERED + " 天");
        }
        return days;
    }

    private static Integer tryParseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), ISO);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return "true".equalsIgnoreCase(trimmed) || "1".equals(trimmed);
    }

    private static SiteSettingsService.KeyValueRequest kv(String key, String value) {
        return new SiteSettingsService.KeyValueRequest(GROUP, key, value);
    }

    // =====================================================================
    // DTO
    // =====================================================================

    /** 保留策略配置（options 供前端下拉）。 */
    public record RetentionConfig(int days, boolean enabled, String cron, int maxDeletePerRun,
                                  List<Integer> options) {
    }

    /** 状态总览。 */
    public record RetentionStatus(RetentionConfig config, RetentionRunReport lastRun,
                                  LocalDateTime nextRunAt, LocalDateTime sqlMonitorLastResetAt) {
    }

    /** 清理预览（无副作用）。 */
    public record RetentionPreview(LocalDateTime cutoff, long totalAuditRows, long expiredAuditRows,
                                   int logDirsToDelete, long logFilesBytes) {
    }

    /** 单次清理报告。 */
    public record RetentionRunReport(String trigger, boolean dryRun, boolean skipped, String message,
                                     LocalDateTime startedAt, LocalDateTime finishedAt,
                                     int retentionDays, LocalDateTime cutoff,
                                     long deletedAuditRows, int batches, boolean capped,
                                     boolean sqlMonitorReset, int deletedLogDirs, long durationMs) {
    }
}
