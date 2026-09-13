package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.BackupRecordMapper;
import com.unicorn.studio.model.BackupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

/**
 * 数据备份（实施方案 §5.6）：
 * - MySQL：mysqldump（--defaults-extra-file 临时凭据文件，用完即删）→ gzip 落盘；
 * - H2（本地开发）：H2 BACKUP TO 命令直接出压缩包；
 * - 触发写 backup_record(running) → @Async 执行（经代理调用，避免自调用退化为同步）→ 完成/失败回写；
 * - 定时每日 02:00；清理仅保留最近 retention-count 份。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 单轮清理最多删除的行数（B-26）：防止 retention-count 配得过小时一次删掉海量历史记录 */
    private static final int MAX_CLEANUP_BATCH = 200;

    private final BackupRecordMapper recordMapper;
    private final ObjectProvider<BackupService> selfProvider;
    private final Path backupDir;
    private final int retentionCount;
    private final String mysqldumpPath;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;

    public BackupService(BackupRecordMapper recordMapper,
                         ObjectProvider<BackupService> selfProvider,
                         @Value("${app.backup.dir:data/backups}") String backupDir,
                         @Value("${app.backup.retention-count:7}") int retentionCount,
                         @Value("${app.backup.mysqldump-path:mysqldump}") String mysqldumpPath,
                         @Value("${spring.datasource.url:}") String jdbcUrl,
                         @Value("${spring.datasource.username:}") String dbUsername,
                         @Value("${spring.datasource.password:}") String dbPassword) {
        this.recordMapper = recordMapper;
        this.selfProvider = selfProvider;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.retentionCount = Math.max(1, retentionCount);
        this.mysqldumpPath = mysqldumpPath;
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    /**
     * 触发备份（异步执行，立即返回记录 id）。
     * 单条 insert 自动提交，保证异步线程能读到记录；经 selfProvider 代理调用
     * @Async 才真正异步（自调用会绕过代理退化为同步执行）。
     */
    public BackupRecord trigger(String type, Long operatorId) {
        BackupRecord record = BackupRecord.builder()
                .type(type)
                .status("running")
                .createdBy(operatorId)
                .build();
        recordMapper.insert(record);
        try {
            selfProvider.getObject().runBackup(record.getId());
        } catch (TaskRejectedException e) {
            // B-04：backupExecutor 用的是 AbortPolicy（备份是分钟级慢 IO，绝不能像埋点那样
            // 用 CallerRuns 兜底把请求线程拖住），因此队列满时提交会在此同步抛出。
            // 那条 status=running 的记录是【提交之前】就已自动提交入库的，若不补偿，
            // 后台备份列表里就会留下一条永不结束的「运行中」备份，且没有任何机制会清理它。
            log.error("备份任务提交被拒绝（队列已满），将记录标记为失败: recordId={}", record.getId(), e);
            record.setStatus("failed");
            record.setErrorMsg("备份任务提交被拒绝：备份队列已满，请稍后重试");
            recordMapper.updateById(record);
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "备份队列已满，请稍后重试");
        }
        return record;
    }

    @Async("backupExecutor")
    public void runBackup(Long recordId) {
        BackupRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        try {
            String fileName = "unicorn_" + FILE_TIME.format(LocalDateTime.now()) + ".backup";
            Path target = backupDir.resolve(fileName).toAbsolutePath().normalize();
            Files.createDirectories(backupDir);

            if (jdbcUrl.contains("mysql")) {
                fileName = "unicorn_" + FILE_TIME.format(LocalDateTime.now()) + ".sql.gz";
                target = backupDir.resolve(fileName).toAbsolutePath().normalize();
                runMysqldump(target);
            } else {
                runH2Backup(target);
            }

            record.setFileName(fileName);
            record.setFileSize(Files.size(target));
            record.setStatus("success");
            record.setErrorMsg(null);
            recordMapper.updateById(record);
            cleanupOldBackups();
        } catch (Exception e) {
            log.error("备份失败 recordId={}", recordId, e);
            record.setStatus("failed");
            record.setErrorMsg(e.getMessage() == null ? "未知错误" : e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)));
            recordMapper.updateById(record);
        }
    }

    /**
     * 执行 mysqldump 并把输出以 gzip 流式落盘。
     * <p>
     * <b>子进程必须显式回收（B-26）</b>：原实现拿到 {@code Process} 后从不 {@code destroy()}。
     * 若 {@code in.transferTo(out)} 因磁盘满或 gzip 异常抛错，异常会从 try 块直接抛出，
     * <b>mysqldump 子进程仍在运行</b>——它继续持有数据库连接与文件句柄，
     * 而备份线程池只有 1 个线程，泄漏会逐轮累积。
     * 故这里把 Process 提到 try 之外，在 finally 里强制回收。
     */
    private void runMysqldump(Path target) throws Exception {
        String dbName = extractDbName(jdbcUrl);
        Path credentials = Files.createTempFile("mysqldump-creds", ".cnf");
        Path errFile = Files.createTempFile("mysqldump-err", ".log");
        Process process = null;
        try {
            Files.writeString(credentials,
                    "[client]\nuser=" + dbUsername + "\npassword=" + dbPassword + "\n",
                    StandardCharsets.UTF_8);
            process = new ProcessBuilder(mysqldumpPath,
                    "--defaults-extra-file=" + credentials,
                    "--single-transaction", "--routines", dbName)
                    // stderr 落临时文件：避免缓冲区填满导致 waitFor 死锁，失败时也能取到错误详情
                    .redirectError(errFile.toFile())
                    .start();
            // stdout 流经 GZIPOutputStream 真正压缩（与 .sql.gz 扩展名一致）
            try (InputStream in = process.getInputStream();
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
                in.transferTo(out);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                String errDetail = Files.exists(errFile) ? Files.readString(errFile, StandardCharsets.UTF_8).strip() : "";
                throw new IOException("mysqldump 退出码 " + exit
                        + (errDetail.isEmpty() ? "" : ": " + errDetail.substring(0, Math.min(errDetail.length(), 300))));
            }
        } finally {
            // 无论成功、失败还是被中断，都不留活着的子进程。
            // 注意顺序：先回收进程，再删临时文件 —— 反过来的话，仍在运行的 mysqldump
            // 会因凭据文件被删而报错，虽然不影响我们释放资源，但会在 stderr 留下误导性的错误。
            if (process != null && process.isAlive()) {
                log.warn("mysqldump 子进程仍在运行，强制回收（备份可能因异常提前中断）");
                process.destroyForcibly();
            }
            Files.deleteIfExists(credentials);
            Files.deleteIfExists(errFile);
        }
    }

    private void runH2Backup(Path target) throws Exception {
        // H2 的 BACKUP TO 是文件级命令（非 SQL 标准），走 JDBC 执行
        String url = jdbcUrl.replace(";MODE=MySQL", "");
        try (Connection conn = DriverManager.getConnection(url, dbUsername, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.execute("BACKUP TO '" + target.toString().replace("'", "''") + "'");
        }
    }

    private String extractDbName(String url) {
        int idx = url.indexOf('?');
        String clean = idx >= 0 ? url.substring(0, idx) : url;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 && slash < clean.length() - 1 ? clean.substring(slash + 1) : clean;
    }

    /**
     * 保留最近 retention-count 份备份。
     * <p>
     * <b>裁剪下推到 DB（B-26）</b>：原实现是 {@code findAllByOrderByCreatedAtDesc()} 把
     * <b>整表载入内存</b>，再 {@code subList(retentionCount, size)} 丢掉前面几条，
     * 最后逐条 {@code deleteById}。备份记录只增不减，这个类的内存占用会随运行年限线性增长，
     * 而它其实只需要「最近 N 条之外的那些」。
     * <p>
     * 三点改动：
     * <ol>
     *   <li>用 {@code LIMIT ... OFFSET retentionCount} 让 DB 直接返回「该删的那批」，
     *       内存占用与表总量脱钩；</li>
     *   <li>排序键由 {@code created_at} 换成 {@code id}：id 是自增主键，插入序即时间序，
     *       且不存在同一秒创建导致排序不稳定的问题（清理逻辑必须确定性地保留「最近 N 份」）；</li>
     *   <li>逐条 {@code deleteById} 换成一次 {@code deleteBatchIds}。</li>
     * </ol>
     * 另加 {@link #MAX_CLEANUP_BATCH} 上限：即使 retention-count 被配得很小而历史记录极多，
     * 单轮也不会一次删掉海量行（每轮备份后都会再跑一次清理）。
     */
    public void cleanupOldBackups() {
        List<BackupRecord> stale;
        try {
            stale = recordMapper.selectList(new LambdaQueryWrapper<BackupRecord>()
                    .orderByDesc(BackupRecord::getId)
                    .last("LIMIT " + MAX_CLEANUP_BATCH + " OFFSET " + retentionCount));
        } catch (Exception e) {
            log.warn("查询待清理备份记录失败: {}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        for (BackupRecord record : stale) {
            if (record.getFileName() != null) {
                try {
                    Files.deleteIfExists(backupDir.resolve(record.getFileName()));
                } catch (IOException e) {
                    log.warn("删除旧备份文件失败: {}", record.getFileName());
                }
            }
        }
        recordMapper.deleteBatchIds(stale.stream().map(BackupRecord::getId).toList());
        log.info("已清理 {} 份过期备份", stale.size());
    }

    @Scheduled(cron = "${app.backup.cron:0 0 2 * * *}")
    public void scheduledBackup() {
        log.info("定时备份触发");
        trigger("scheduled", null);
    }

    public PageResult<BackupRecord> list(int page, int size) {
        Page<BackupRecord> records = recordMapper.selectPage(
                PageResult.safePage(page + 1, size),
                new LambdaQueryWrapper<BackupRecord>().orderByDesc(BackupRecord::getId));
        return PageResult.of(records);
    }

    /** 备份文件下载（校验记录存在且成功，且解析结果必须位于备份目录内）。 */
    public Path resolveDownloadFile(Long id) {
        BackupRecord record = Optional.ofNullable(recordMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在"));
        if (!"success".equals(record.getStatus()) || record.getFileName() == null) {
            throw BusinessException.badRequest("备份尚未完成或已失败");
        }
        Path file = backupDir.resolve(record.getFileName()).normalize();
        if (!file.startsWith(backupDir)) {
            throw BusinessException.badRequest("非法文件路径");
        }
        if (!Files.exists(file)) {
            throw BusinessException.notFound("备份文件已清理");
        }
        return file;
    }

    @Transactional
    public void delete(Long id) {
        BackupRecord record = Optional.ofNullable(recordMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在"));
        if (record.getFileName() != null) {
            try {
                Files.deleteIfExists(backupDir.resolve(record.getFileName()));
            } catch (IOException e) {
                log.warn("删除备份文件失败: {}", record.getFileName());
            }
        }
        recordMapper.deleteById(record.getId());
    }
}
