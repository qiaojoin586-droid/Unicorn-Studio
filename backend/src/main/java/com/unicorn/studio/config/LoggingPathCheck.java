package com.unicorn.studio.config;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.logging.LogFile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/** 输出真实文件路径，并检查文件输出是否已挂载；不依赖自定义 appender 名称。 */
@Component
@Order(Integer.MAX_VALUE)
public class LoggingPathCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger("LOG_PATH_CHECK");
    private final Environment environment;

    public LoggingPathCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        LogFile configured = LogFile.get(environment);
        if (configured == null) {
            log.warn("未启用文件日志，请配置 logging.file.name");
            return;
        }
        Path file = Path.of(configured.toString()).toAbsolutePath().normalize();
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)
                || !hasStartedFileAppender(context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders(), file)
                || !Files.isRegularFile(file) || !Files.isWritable(file)) {
            String message = "日志落点自检失败：文件输出未启用或文件不可写，预期文件 " + file;
            System.err.println(message);
            throw new IllegalStateException(message);
        }
        log.info("日志落点自检通过：日志文件 {}，工作目录 {}", file, System.getProperty("user.dir"));
    }

    private static boolean hasStartedFileAppender(Iterator<Appender<ILoggingEvent>> appenders, Path expected) {
        while (appenders.hasNext()) {
            Appender<ILoggingEvent> appender = appenders.next();
            if (!appender.isStarted()) {
                continue;
            }
            if (appender instanceof FileAppender<?> file && file.getFile() != null
                    && expected.equals(Path.of(file.getFile()).toAbsolutePath().normalize())) {
                return true;
            }
            if (appender instanceof AsyncAppender async && hasStartedFileAppender(async.iteratorForAppenders(), expected)) {
                return true;
            }
        }
        return false;
    }
}
