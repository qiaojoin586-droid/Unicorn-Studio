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

/**
 * 启动时把「日志到底写到哪」显式打出来。
 *
 * <h2>要解决的判定难题：日志文件不见了，但现场一切正常</h2>
 * {@code logging.file.path} 默认值是 {@code ./logs} —— <b>相对 JVM 工作目录</b>解析
 * （Spring Boot 的 {@code LogFile.applyTo()} 把这个字符串原样发布成系统属性 {@code LOG_PATH}，
 * 不做绝对化，logback 拿到的就是 {@code ./logs}）。
 * <p>
 * 本地 {@code java -jar} / IDEA Run 的工作目录恰好是项目根，于是日志落在项目里，看起来没问题；
 * 换到 Docker / systemd 之后工作目录一变（或挂载点与它不一致），日志就落到别处 ——
 * 而<b>这个过程是完全静默的</b>：logback 成功创建目录、成功写入文件，没有任何错误可言，
 * 唯一的现象就是「你去看的那个目录里没有日志」。
 *
 * <h2>实测结论（2026-09-27，Boot 3.3.5，务必先读）</h2>
 * 本类的失败分支在实践中几乎不可达，因为<b>目录不可写时应用根本起不来</b>：
 * logback 在配置阶段启动 appender 失败 → Spring Boot 的
 * {@code LogbackLoggingSystem.reportConfigurationErrorsIfNecessary()} 直接抛
 * {@code IllegalStateException} 终止启动，控制台会打出完整堆栈：
 * <pre>
 * Logging system failed to initialize using configuration from 'null'
 * java.lang.IllegalStateException: Logback configuration error detected:
 * ERROR in RollingFileAppender[INFO_FILE] - Failed to create parent directories for [.../info/2026-09-27/app-info.log.0]
 * ...
 * ERROR o.s.boot.SpringApplication : Application run failed
 * </pre>
 * 也就是说：<b>「容器能正常提供服务但没有日志」只可能是路径错位，不可能是权限问题</b>
 * ——权限问题会让容器根本 Up 不起来（在 {@code --restart always} 下表现为反复重启）。
 * 这条判据能把排查范围直接砍一半，故在此类里也顺带把落点印出来供对照。
 *
 * <h2>为什么读 logback 自己的配置而不是复刻解析规则</h2>
 * 直接遍历 logback 的 appender 取 {@code fileNamePattern}，拿到的是<b>属性已解析后</b>
 * logback 真正会用的模板（{@code ./logs/info/%d{yyyy-MM-dd}/app-info.log.%i} 这种），
 * 比在代码里再算一遍「相对路径按工作目录展开」更权威、也不会随 logback 版本漂移。
 *
 * <h2>为什么输出走 System.err</h2>
 * prod profile 下 CONSOLE appender 是刻意不挂载的（见 logback-spring.xml），
 * {@code docker logs} 里本来就什么都没有；而这行信息恰恰是要在 {@code docker logs}
 * 里被看到的。{@code System.err} 由容器主进程直接持有 fd 2，与 logback 的健康状态无关，
 * 是唯一不依赖日志系统本身的输出通道。
 *
 * <h2>容错</h2>
 * 任何异常都只记一条日志、<b>绝不阻断启动</b> —— 与 {@link UploadIntegrityCheck} 同一取向。
 */
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
