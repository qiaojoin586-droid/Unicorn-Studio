package com.unicorn.studio.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


@Component
@Order(Integer.MAX_VALUE)
public class LoggingPathCheck implements ApplicationRunner {

    /** 独立 logger 名（无点号命名空间）：不被 application-prod.yml 里 com.unicorn.studio: WARN 压掉 */
    private static final Logger log = LoggerFactory.getLogger("LOG_PATH_CHECK");

    /** 探针文件名：固定名 + 用完即删，不在日志目录里留垃圾 */
    private static final String PROBE_NAME = ".log-write-probe";

    private final Environment environment;

    public LoggingPathCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String workingDir = System.getProperty("user.dir");

            // ① logback 眼中的真实模板（属性已解析）
            List<String> patterns = logbackPatterns();

            // ② 配置原值：Spring Boot 把 logging.file.path 原样发布成系统属性 LOG_PATH
            String raw = System.getProperty("LOG_PATH");
            if (raw == null || raw.isBlank()) {
                raw = environment.getProperty("logging.file.path");
            }
            boolean unset = (raw == null || raw.isBlank());
            if (unset) {
                // logback 的兜底：${LOG_PATH:-${user.dir}/logs}
                raw = "./logs";
            }
            String display = unset ? raw + "   (未配置，logback 退回 ${user.dir}/logs)" : raw;

            // ③ 按 logback 的规则解析成绝对路径：相对路径锚定 JVM 工作目录
            Path resolved = resolve(raw, workingDir);

            // ④ 真写一个探针文件
            String failure = probe(resolved);

            StringBuilder sb = new StringBuilder();
            sb.append("\n==================== [LOG-PATH] ====================\n");
            sb.append("文件日志落点自检\n");
            sb.append("  配置原值      : ").append(display).append('\n');
            sb.append("                  (logging.file.path 被原样发布为系统属性 LOG_PATH，不做绝对化)\n");
            sb.append("  实际落点      : ").append(resolved).append('\n');
            sb.append("  当前工作目录  : ").append(workingDir).append('\n');
            sb.append("  可写性        : ").append(failure == null ? "可写 ✔" : "不可写 ✘  " + failure).append('\n');
            if (!patterns.isEmpty()) {
                sb.append("  logback 模板  :\n");
                for (String p : patterns) {
                    sb.append("      ").append(p).append('\n');
                }
            }
            sb.append("  对照提示      : 容器部署时，请确认「实际落点」与 -v 的容器侧目标路径【逐字一致】；\n");
            sb.append("                  不一致 = 宿主机挂载目录永远为空，而日志其实写在容器内别处。\n");
            sb.append("                  本目录不可写时应用会启动失败（Boot 的 LogbackLoggingSystem\n");
            sb.append("                  在配置阶段直接抛 IllegalStateException），不会「跑着但没日志」。\n");
            sb.append("====================================================\n");

            if (failure == null) {
                // 成功也走 stderr：这条信息就要在 docker logs 第一屏看得见
                System.err.println(sb);
                log.info("日志落点自检通过：配置值 [{}]，实际落点 {}，工作目录 {}", display, resolved, workingDir);
                return;
            }

            System.err.println(sb);
            log.error("日志目录不可写（配置值 [{}]，落点 {}，工作目录 {}）：{}", display, resolved, workingDir, failure);
        } catch (Exception e) {
            // 诊断逻辑绝不阻断启动
            log.warn("日志落点自检未能完成（不影响服务启动）: {}", e.getMessage());
        }
    }

    /** 相对路径按工作目录展开 —— 与 logback 的 new File(相对路径) 规则一致。 */
    private static Path resolve(String raw, String workingDir) {
        Path base = Path.of(workingDir).toAbsolutePath().normalize();
        try {
            Path p = Path.of(raw.trim());
            return (p.isAbsolute() ? p : base.resolve(p)).toAbsolutePath().normalize();
        } catch (Exception e) {
            // 路径非法（Windows 保留字符等）：退回工作目录，至少让对照有意义
            return base;
        }
    }

    /**
     * 遍历 logback 的 root appender，取出各滚动策略的 fileNamePattern。
     * 项目的 appender 都被 AsyncAppender 包了一层（见 logback-spring.xml），故需要拆一层。
     */
    private static List<String> logbackPatterns() {
        List<String> out = new ArrayList<>();
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
                return out;
            }
            ch.qos.logback.classic.Logger root = ctx.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                Appender<ILoggingEvent> appender = it.next();
                if (appender instanceof AsyncAppender async) {
                    for (Iterator<Appender<ILoggingEvent>> inner = async.iteratorForAppenders(); inner.hasNext(); ) {
                        addPattern(out, inner.next());
                    }
                } else {
                    addPattern(out, appender);
                }
            }
        } catch (Exception e) {
            // 日志系统未按预期初始化时静默降级：本检查不该成为故障源
        }
        return out;
    }

    private static void addPattern(List<String> into, Appender<ILoggingEvent> appender) {
        if (appender instanceof RollingFileAppender<?> rfa
                && rfa.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> policy) {
            String pattern = policy.getFileNamePattern();
            if (pattern != null && !pattern.isBlank() && !into.contains(pattern)) {
                into.add(pattern);
            }
        }
    }

    /**
     * 真写一个探针文件并删除。
     *
     * @return 成功返回 {@code null}；失败返回人类可读的原因
     */
    private static String probe(Path dir) {
        Path probeFile = dir.resolve(PROBE_NAME);
        try {
            Files.createDirectories(dir);
            Files.writeString(probeFile, "probe", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            long size = Files.size(probeFile);
            Files.deleteIfExists(probeFile);
            return size > 0 ? null : "写入后文件为空（磁盘可能已满）";
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
