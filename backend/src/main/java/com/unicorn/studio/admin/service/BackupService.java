package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.BackupRecordMapper;
import com.unicorn.studio.model.BackupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
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

/**
 * 数据备份（实施方案 §5.6）：
 * - MySQL：mysqldump（--defaults-extra-file 临时凭据文件，用完即删）→ gzip 落盘；
 * - H2（本地开发）：H2 BACKUP TO 命令直接出压缩包；
 * - 触发写 backup_record(running) → @Async 执行 → 完成/失败回写；
 * - 定时每日 02:00；清理仅保留最近 retention-count 份。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final BackupRecordMapper recordMapper;
    private final Path backupDir;
    private final int retentionCount;
    private final String mysqldumpPath;
    private final String jdbcUrl;
    private final String dbUsername;
    private final String dbPassword;

    public BackupService(BackupRecordMapper recordMapper,
                         @Value("${app.backup.dir:data/backups}") String backupDir,
                         @Value("${app.backup.retention-count:7}") int retentionCount,
                         @Value("${app.backup.mysqldump-path:mysqldump}") String mysqldumpPath,
                         @Value("${spring.datasource.url:}") String jdbcUrl,
                         @Value("${spring.datasource.username:}") String dbUsername,
                         @Value("${spring.datasource.password:}") String dbPassword) {
        this.recordMapper = recordMapper;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.retentionCount = Math.max(1, retentionCount);
        this.mysqldumpPath = mysqldumpPath;
        this.jdbcUrl = jdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
    }

    /** 触发备份（异步执行，立即返回记录 id）。 */
    @Transactional
    public BackupRecord trigger(String type, Long operatorId) {
        BackupRecord record = BackupRecord.builder()
                .type(type)
                .status("running")
                .createdBy(operatorId)
                .build();
        recordMapper.insert(record);
        runBackup(record.getId());
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

    private void runMysqldump(Path target) throws Exception {
        String dbName = extractDbName(jdbcUrl);
        Path credentials = Files.createTempFile("mysqldump-creds", ".cnf");
        try {
            Files.writeString(credentials,
                    "[client]\nuser=" + dbUsername + "\npassword=" + dbPassword + "\n",
                    StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(mysqldumpPath,
                    "--defaults-extra-file=" + credentials,
                    "--single-transaction", "--routines", dbName)
                    .redirectOutput(target.toFile())
                    .start();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("mysqldump 退出码 " + exit);
            }
        } finally {
            Files.deleteIfExists(credentials);
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

    /** 保留最近 N 份成功备份（按创建时间）。 */
    @Transactional
    public void cleanupOldBackups() {
        List<BackupRecord> records = recordMapper.findTop7ByOrderByCreatedAtDesc();
        if (records.size() <= retentionCount) {
            return;
        }
        for (BackupRecord record : records.subList(retentionCount, records.size())) {
            if (record.getFileName() != null) {
                try {
                    Files.deleteIfExists(backupDir.resolve(record.getFileName()));
                } catch (IOException e) {
                    log.warn("删除旧备份文件失败: {}", record.getFileName());
                }
            }
            recordMapper.deleteById(record.getId());
        }
    }

    @Scheduled(cron = "${app.backup.cron:0 0 2 * * *}")
    public void scheduledBackup() {
        log.info("定时备份触发");
        trigger("scheduled", null);
    }

    public PageResult<BackupRecord> list(int page, int size) {
        Page<BackupRecord> records = recordMapper.selectPage(
                new Page<>(page + 1, size),
                new LambdaQueryWrapper<BackupRecord>().orderByDesc(BackupRecord::getId));
        return PageResult.of(records);
    }

    /** 备份文件下载（校验记录存在且成功）。 */
    public Path resolveDownloadFile(Long id) {
        BackupRecord record = Optional.ofNullable(recordMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("备份记录不存在"));
        if (!"success".equals(record.getStatus()) || record.getFileName() == null) {
            throw BusinessException.badRequest("备份尚未完成或已失败");
        }
        Path file = backupDir.resolve(record.getFileName()).normalize();
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
