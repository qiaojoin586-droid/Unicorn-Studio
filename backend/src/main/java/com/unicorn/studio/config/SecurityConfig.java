package com.unicorn.studio.config;

import com.unicorn.studio.common.RateLimitFilter;
import com.unicorn.studio.common.security.PublicUserAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 公开过滤器链（@Order(2)）：/api/**（含前台接口、PV 埋点、管理端登录）。
 * permitAll + 各接口自带限流（RateLimitFilter 按路径策略配置桶）。
 * <p>
 * 链内顺序：RateLimitFilter（限流）→ PublicUserAuthFilter（认前台身份）。
 * 链内顺序只能用 addFilterBefore/After 表达，@Order 管的是「哪条链优先匹配」
 * （见 AdminSecurityConfig 连续两次 addFilterBefore 实现「限流 → 认证」）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain publicChain(HttpSecurity http,
                                          RateLimitFilter rateLimitFilter,
                                          PublicUserAuthFilter publicUserAuthFilter) throws Exception {
        http.securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            // R2 前台身份过滤器：只认人、不拦人；「必须登录」由 @RequireSiteUser 在方法级决定
            .addFilterAfter(publicUserAuthFilter, RateLimitFilter.class);

        return http.build();
    }
}
