package com.unicorn.studio.config;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Boot 日志初始化、文件 IO 和压缩归档，不连接数据库。 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingConfigurationTest {

    private static final List<String> LOGGING_PROPERTIES = List.of(
            "LOG_PATH", "LOG_FILE", "CONSOLE_LOG_CHARSET", "FILE_LOG_CHARSET",
            "LOGBACK_ROLLINGPOLICY_FILE_NAME_PATTERN", "LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE",
            "LOGBACK_ROLLINGPOLICY_MAX_HISTORY", "LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP",
            "LOGBACK_ROLLINGPOLICY_CLEAN_HISTORY_ON_START");

    @TempDir
    Path logDir;
    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void rememberLoggingProperties() {
        LOGGING_PROPERTIES.forEach(key -> originalProperties.put(key, System.getProperty(key)));
    }

    @AfterEach
    void resetLogging() {
        loggerContext().stop();
        LoggingSystem.get(getClass().getClassLoader()).cleanUp();
        originalProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savesAllLogsInOneFile(boolean production, CapturedOutput output) throws IOException {
        assertThat(getClass().getClassLoader().getResource("logback-spring.xml")).isNull();
        String profile = production ? "--spring.profiles.active=prod" : "--spring.profiles.active=default";
        try (var application = start(profile)) {
            var runtime = LoggerFactory.getLogger("com.unicorn.studio.logging.Regression");
            runtime.info("RUNTIME-INFO 中文运行日志");
            runtime.warn("RUNTIME-WARN");
            runtime.error("RUNTIME-ERROR", new IllegalStateException("异常堆栈测试"));
            LoggerFactory.getLogger("com.unicorn.studio.mapper.LoggingSmoke").debug("SQL-DEBUG-SHOULD-BE-OFF");
            LoggerFactory.getLogger("ACCESS_LOG").info("[ACCESS] GET /logging-smoke status=200");
            var audit = LoggerFactory.getLogger("ADMIN_AUDIT");
            audit.info("[AUDIT] audit-success");
            audit.error("[AUDIT] audit-failure");
            if (production) {
                for (int i = 0; i < 3000; i++) {
                    runtime.info("BURST-{}", i);
                }
            }
            // 运行期间即已写入磁盘，不需要等进程退出。
            assertThat(readAppLog()).contains("RUNTIME-INFO 中文运行日志", "audit-success");
        }
        loggerContext().stop();

        String content = readAppLog();
        assertThat(content).contains("Starting LoggingConfigurationTest", "Started LoggingConfigurationTest",
                        "日志落点自检通过", logDir.resolve("app.log").toString(),
                        "RUNTIME-INFO 中文运行日志", "RUNTIME-WARN", "RUNTIME-ERROR",
                        "java.lang.IllegalStateException: 异常堆栈测试",
                        "[ACCESS] GET /logging-smoke status=200", "audit-success", "audit-failure")
                .doesNotContain("SQL-DEBUG-SHOULD-BE-OFF", "四类文件输出", "日志落点自检失败");
        for (String oldPath : List.of("info", "warn", "error", "admin", "spring.log")) {
            assertThat(logDir.resolve(oldPath)).doesNotExist();
        }
        assertThat(output).contains("RUNTIME-INFO", "audit-success");
        if (production) {
            assertThat(content.lines().filter(line -> line.contains("BURST-")).count()).isEqualTo(3000);
        }
    }

    @Test
    void honorsExplicitFileName(CapturedOutput output) throws IOException {
        Path customFile = logDir.resolve("custom").resolve("server.log");
        try (var application = start("--logging.file.name=" + customFile)) {
            LoggerFactory.getLogger("ACCESS_LOG").info("CUSTOM-FILE-MARKER");
        }
        loggerContext().stop();
        assertThat(Files.readString(customFile, StandardCharsets.UTF_8))
                .contains("CUSTOM-FILE-MARKER", customFile.toString(), "日志落点自检通过");
        assertThat(logDir.resolve("app.log")).doesNotExist();
        assertThat(output).doesNotContain("日志落点自检失败");
    }

    @Test
    void reportsStoppedFileOutputEvenWhenFileAlreadyExists(CapturedOutput output) {
        try (var application = start()) {
            loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE").stop();
            assertThatThrownBy(() -> new LoggingPathCheck(application.getEnvironment()).run(new DefaultApplicationArguments()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("日志落点自检失败");
            assertThat(output).contains("日志落点自检失败：文件输出未启用或文件不可写");
        }
    }

    @Test
    void savesStartupFailure() throws IOException {
        assertThatThrownBy(() -> start("--logging.smoke.fail-startup=true"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("STARTUP-FAILURE-MARKER");
        loggerContext().stop();
        assertThat(readAppLog()).contains("Application run failed", "STARTUP-FAILURE-MARKER");
    }

    @Test
    void failsStartupWhenLogTargetIsADirectory() throws IOException {
        Files.createDirectory(logDir.resolve("app.log"));
        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollsAndCompressesLogsWithoutLosingMessages() throws IOException {
        try (var application = start("--logging.logback.rollingpolicy.max-file-size=2KB")) {
            var logger = LoggerFactory.getLogger("ACCESS_LOG");
            for (int i = 0; i < 300; i++) {
                logger.info("ROTATION-{} {}", i, "日志内容".repeat(50));
            }
        }
        loggerContext().stop();

        List<Path> archives;
        try (var files = Files.list(logDir)) {
            archives = files.filter(path -> path.getFileName().toString().matches(
                    "app\\.log\\.\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.gz")).toList();
        }
        assertThat(archives).isNotEmpty();
        StringBuilder allLogs = new StringBuilder(readAppLog());
        for (Path archive : archives) {
            try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
                allLogs.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(allLogs.toString().lines().filter(line -> line.contains("ROTATION-")).count()).isEqualTo(300);
    }

    @Test
    void defaultsToLogsUnderActualWorkingDirectory() throws Exception {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path consoleOutput = logDir.resolve("process-output.txt");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", javaName).toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LoggingSmokeMain.class.getName(),
                "--spring.config.location=classpath:/application.yml")
                .directory(logDir.toFile()).redirectErrorStream(true).redirectOutput(consoleOutput.toFile());
        for (String key : List.of("LOG_DIR", "LOGGING_FILE_NAME", "LOGGING_FILE_PATH", "LOGGING_CONFIG")) {
            builder.environment().remove(key);
        }
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue())
                    .withFailMessage(() -> readProcessOutput(consoleOutput)).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        assertThat(Files.readString(logDir.resolve("logs/app.log"), StandardCharsets.UTF_8))
                .contains("WORKING-DIRECTORY-MARKER", "日志落点自检通过");
    }

    private ConfigurableApplicationContext start(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("--spring.config.location=classpath:/application.yml"));
        // 使用 Boot 的标准文件名覆盖，测试始终隔离在临时目录，不依赖部署文件的路径写法。
        if (List.of(extraArgs).stream().noneMatch(arg -> arg.startsWith("--logging.file.name="))) {
            args.add("--logging.file.name=" + logDir.resolve("app.log"));
        }
        args.addAll(List.of(extraArgs));
        return newApplication().run(args.toArray(String[]::new));
    }

    private static SpringApplication newApplication() {
        var app = new SpringApplication(LoggingOnlyApplication.class);
        app.setMainApplicationClass(LoggingConfigurationTest.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.setBannerMode(Banner.Mode.OFF);
        return app;
    }

    private String readAppLog() throws IOException {
        return Files.readString(logDir.resolve("app.log"), StandardCharsets.UTF_8);
    }

    private static String readProcessOutput(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    public static class LoggingSmokeMain {
        public static void main(String[] args) {
            try (var application = newApplication().run(args)) {
                LoggerFactory.getLogger("ACCESS_LOG").info("WORKING-DIRECTORY-MARKER");
            } finally {
                loggerContext().stop();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LoggingOnlyApplication {
        @Bean
        LoggingPathCheck loggingPathCheck(Environment environment) {
            return new LoggingPathCheck(environment);
        }

        @Bean
        @ConditionalOnProperty(name = "logging.smoke.fail-startup", havingValue = "true")
        Object failedStartup() {
            throw new IllegalStateException("STARTUP-FAILURE-MARKER");
        }
    }
}
