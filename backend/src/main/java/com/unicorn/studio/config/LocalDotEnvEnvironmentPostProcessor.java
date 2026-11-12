package com.unicorn.studio.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从外部 UTF-8 .env 读取本地配置；环境变量和 JVM 参数优先，凭据不作为 classpath 资源加载。 */
public class LocalDotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("APP_ENV_FILE");
        List<Path> candidates = configured == null || configured.isBlank()
                ? List.of(Path.of(".env"), Path.of("backend/src/main/resources/.env"), Path.of("src/main/resources/.env"))
                : List.of(Path.of(configured));
        Path file = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
        if (file == null) {
            if (configured != null && !configured.isBlank()) throw new IllegalStateException("APP_ENV_FILE 指定的文件不存在");
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("local-dotenv", read(file)));
    }

    static Map<String, Object> read(Path file) {
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int separator = line.indexOf('=');
                if (separator < 1) throw invalidLine(i);
                String name = line.substring(0, separator).trim();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalidLine(i);
                String value = line.substring(separator + 1).trim();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    char quote = value.charAt(0);
                    StringBuilder unquoted = new StringBuilder();
                    int end = -1;
                    for (int j = 1; j < value.length(); j++) {
                        char next = value.charAt(j);
                        if (quote == '"' && next == '\\' && j + 1 < value.length()
                                && (value.charAt(j + 1) == '"' || value.charAt(j + 1) == '\\')) {
                            unquoted.append(value.charAt(++j));
                        } else if (next == quote) {
                            end = j;
                            break;
                        } else unquoted.append(next);
                    }
                    if (end < 0 || !(value.substring(end + 1).isBlank() || value.substring(end + 1).trim().startsWith("#")))
                        throw invalidLine(i);
                    value = unquoted.toString();
                } else value = value.replaceFirst("\\s+#.*$", "").stripTrailing();
                values.put(name, value);
            }
            return values;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取本地 .env 配置文件");
        }
    }

    private static IllegalStateException invalidLine(int index) {
        // 不附带原文，以免解析错误把密钥写进启动日志。
        return new IllegalStateException(".env 第 " + (index + 1) + " 行格式不正确，应为 KEY=VALUE");
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
