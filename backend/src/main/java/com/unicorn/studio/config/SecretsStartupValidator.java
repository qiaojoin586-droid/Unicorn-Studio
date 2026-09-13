package com.unicorn.studio.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期校验「外部依赖凭据必须显式配置」（S-4）。
 * <p>
 * 背景：{@code application.yml} 曾把<b>真实凭据</b>作为默认值写死并随仓库公开 ——
 * Redis 是真实公网 IP + 真实口令，MySQL 是弱口令 {@code 123456}。这些值已进入 git 历史，
 * 视为已泄漏，必须轮换；代码侧能做的则是<b>移除默认值并让「未配置」变成启动失败</b>，
 * 而不是静默使用一个公开的凭据。
 * <p>
 * 与 {@code JwtService} / {@code PublicAuthService} / {@code CryptoService} 的 fail-closed
 * 风格统一：配置缺失是<b>部署错误</b>，应当在启动时以明确信息暴露。
 * <p>
 * <b>为什么是 {@link ApplicationContextInitializer} 而不是 {@code @Component}</b>：
 * 实测发现 Bean 版的校验跑不过 Druid —— {@code DruidDataSource} 在初始化时会先行建连，
 * 缺口令时抛出的是 {@code Access denied for user 'root'@'localhost' (using password: NO)}，
 * 校验器根本没机会执行，运维看到的是「拒绝访问」而不是「你该配 DB_PASSWORD」。
 * Initializer 在 Environment 就绪之后、<b>任何 Bean 实例化之前</b>执行，因此它的报错一定是
 * 用户看到的那一条。注册见 {@code META-INF/spring.factories}。
 * <p>
 * 校验项刻意保持最小（只查「是否为空」），不校验强度/可达性 —— 那些由连接池在启动时自然暴露。
 */
public class SecretsStartupValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();

        List<String> missing = new ArrayList<>();

        if (!StringUtils.hasText(env.getProperty("spring.datasource.password"))) {
            missing.add("DB_PASSWORD          → spring.datasource.password（MySQL 口令）");
        }

        // 生产 JwtService / PublicAuthService / CryptoService 各自也有启动守卫，
        // 这里提前到同一处汇总，让「一次启动失败就能看全所有缺失项」，而不是修一个报一个。
        if (!StringUtils.hasText(env.getProperty("app.admin.jwt-secret"))) {
            missing.add("ADMIN_JWT_SECRET     → app.admin.jwt-secret（后台 token 签名，≥32 字符）");
        }
        if (!StringUtils.hasText(env.getProperty("app.public.jwt-secret"))) {
            missing.add("PUBLIC_JWT_SECRET    → app.public.jwt-secret（前台 token 签名，≥32 字符）");
        }
        if (!StringUtils.hasText(env.getProperty("app.crypto.secret"))) {
            missing.add("APP_CRYPTO_SECRET    → app.crypto.secret（敏感配置加密，≥32 字符随机串）");
        }

        // 走哨兵拓扑时 host/password 允许为空（由 sentinel.nodes 决定连接目标）
        boolean sentinelMode = StringUtils.hasText(env.getProperty("spring.data.redis.sentinel.nodes"));
        if (!sentinelMode) {
            if (!StringUtils.hasText(env.getProperty("spring.data.redis.host"))) {
                missing.add("REDIS_HOST           → spring.data.redis.host（Redis 主机）");
            }
            if (!StringUtils.hasText(env.getProperty("spring.data.redis.password"))) {
                missing.add("REDIS_PASSWORD       → spring.data.redis.password（Redis 口令）");
            }
        }

        if (!missing.isEmpty()) {
            String detail = String.join("\n  - ", missing);
            // 用 IllegalStateException 而非 log.error：此阶段日志系统尚未完全初始化，
            // 异常信息由 Spring Boot 的失败分析器原样打印，是此刻最可靠的传达方式。
            throw new IllegalStateException(
                    "以下凭据未配置，拒绝启动：\n  - " + detail + "\n"
                            + "本地开发：写入 backend/src/main/resources/application-local.yml"
                            + "（不入库，模板见 application-local.yml.example）\n"
                            + "生产部署：通过环境变量注入；若 Redis 走哨兵模式请设置 REDIS_SENTINEL_NODES");
        }
    }
}
