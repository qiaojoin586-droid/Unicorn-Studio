package com.unicorn.studio.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 双过滤器链（实施方案 §5.1）：
 * - @Order(1) 后台管理链：/api/admin/**，JWT 认证 + @PreAuthorize，命中后不再进入公开链；
 * - @Order(2) 公开链在 SecurityConfig（permitAll + 各接口限流）。
 * 两条链均 STATELESS + csrf.disable（Bearer 认证的标准做法）。
 */
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    private final ObjectMapper objectMapper;

    public AdminSecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminChain(HttpSecurity http,
                                          JwtAuthFilter jwtFilter,
                                          RateLimitFilter rateLimitFilter) throws Exception {
        http.securityMatcher("/api/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录/刷新公开（受 RateLimitFilter IP 限流），其余 admin 接口需认证
                        .requestMatchers("/api/admin/auth/login", "/api/admin/auth/refresh").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeJson(res, 401, 40100, "未认证或登录已过期"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeJson(res, 403, 40300, "无操作权限")));
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, int code, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Map.of 不允许 null 值，data 用 HashMap 承载
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("data", null);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
