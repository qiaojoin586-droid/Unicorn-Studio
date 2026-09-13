package com.unicorn.studio.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * <p>部署拓扑（易踩坑）：前台可由两种方式提供——
 * <ul>
 *   <li>Vite dev server（默认 5173）——浏览器直接访问，Origin 为 {@code http://localhost:5173}</li>
 *   <li>Node 生产服务（{@code frontend/server/index.js}，默认 3000）——它再把 {@code /api/*}
 *       反向代理到本后端。此时浏览器 Origin 是 Node 服务地址（如 {@code http://127.0.0.1:3000}），
 *       与 5173 属**不同 Origin**，若未加入白名单，后端会直接返回
 *       {@code 403 Invalid CORS request}，前端表现为"接口 200/无响应但收不到数据"。</li>
 * </ul>
 *
 * <p>注意：{@code localhost} 与 {@code 127.0.0.1} 在浏览器中视为两个不同 Origin，
 * 必须分别列出；否则用 127.0.0.1 访问时仍会 403。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 精确 Origin 白名单（逗号分隔）。默认覆盖：前台 dev 5173、后台 dev 5174、
     * 前台 Node 生产服务 3000（localhost 与 127.0.0.1 两种写法）。
     * 生产环境可通过 {@code APP_CORS_ORIGINS} 覆盖。
     */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOrigins;

    /**
     * Origin 通配模式（逗号分隔），用于容忍端口变动（如 Vite 自动切换到 5174/5175、
     * Node 服务改端口）。默认只放开本机任意端口的 http 访问，不涉及公网域名，
     * 生产部署请通过 {@code APP_CORS_ORIGIN_PATTERNS} 显式收窄或留空。
     */
    @Value("${app.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*]}")
    private String allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // trim + 过滤空项：环境变量批量配置时逗号后常带空格，未去空格会导致 Origin 精确匹配失败
        String[] origins = splitAndTrim(allowedOrigins);
        String[] patterns = splitAndTrim(allowedOriginPatterns);

        var registration = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);

        // allowedOrigins 与 allowedOriginPatterns 是两条独立通道，命中任一即放行。
        // 二者可同时配置（Spring 支持），避免"只配精确清单导致端口一变就 403"。
        if (origins.length > 0) {
            registration.allowedOrigins(origins);
        }
        if (patterns.length > 0) {
            registration.allowedOriginPatterns(patterns);
        }
    }

    private static String[] splitAndTrim(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
