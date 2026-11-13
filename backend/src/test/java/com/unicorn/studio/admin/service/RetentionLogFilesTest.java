package com.unicorn.studio.admin.service;

import com.unicorn.studio.mapper.SysOperationLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionLogFilesTest {

    @TempDir
    Path directory;

    @Test
    void previewsOnlyLegacyAuditDirectoriesBesideConfiguredLogFile() throws Exception {
        Path logDir = directory.resolve("custom-logs");
        Path expired = logDir.resolve("admin").resolve(LocalDate.now().minusDays(45).toString());
        Path recent = logDir.resolve("admin").resolve(LocalDate.now().toString());
        Files.createDirectories(expired);
        Files.createDirectories(recent);
        Files.writeString(expired.resolve("app-admin.log.0"), "legacy", StandardCharsets.UTF_8);
        Path activeLog = logDir.resolve("app.log");
        Path archive = logDir.resolve("app.log." + LocalDate.now().minusDays(45) + ".0.gz");
        Files.writeString(activeLog, "active", StandardCharsets.UTF_8);
        Files.writeString(archive, "archive", StandardCharsets.UTF_8);

        // 预览只需查询行数，不依赖数据库、Redis 或 Mockito agent。
        var mapper = (SysOperationLogMapper) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SysOperationLogMapper.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("selectCount")) {
                        return 0L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        var settings = new SiteSettingsService(null, null, null) {
            @Override
            public Map<String, String> listGroup(String groupName) {
                return Map.of("retention.days", "7", "retention.enabled", "true");
            }
        };
        var service = new RetentionService(mapper, null, settings, "0 30 3 * * *", 50000, activeLog.toString());

        var preview = service.preview();
        assertThat(preview.logDirsToDelete()).isEqualTo(1);
        assertThat(preview.logFilesBytes()).isEqualTo(6);
        assertThat(service.cleanup(true, "manual", false).message()).contains("旧版审计文件目录 1 个");
        assertThat(expired).exists();
        assertThat(recent).exists();
        assertThat(Files.readString(activeLog)).isEqualTo("active");
        assertThat(Files.readString(archive)).isEqualTo("archive");
    }
}
