package com.unicorn.studio.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * actuator 端点的安全边界（B-19 / S-6）。
 * <p>
 * <b>为什么需要一条独立的链，而不是依赖「管理端口只绑 127.0.0.1」</b>：
 * 端口绑定是<b>部署级</b>的 —— 改一行 {@code management.server.address} 就失效；
 * 而 actuator 的暴露面应当是<b>代码级</b>的安全边界，不随配置漂移。
 * 这条链的作用是：<b>即使有人把管理端口重新暴露到 0.0.0.0（或把 actuator 挪回主端口），
 * 也只有回环来源能访问</b>。
 * <p>
 * <b>为什么是 {@code @Order(0)}</b>：必须早于现有的两条链
 * ——{@code AdminSecurityConfig} 的 {@code @Order(1)}（{@code /api/admin/**}）
 * 与 {@code SecurityConfig} 的 {@code @Order(2)}（{@code /api/**}，其策略是 {@code permitAll}）。
 * 后者尤其关键：actuator 绝不能落进一条 {@code permitAll} 的链里。
 * <p>
 * <b>危害说明（为什么这条链值得单独写一个类）</b>：{@code /actuator/heapdump} 导出的是
 * <b>整个堆</b> —— 其中驻留着 {@code AiRuntimeConfig} 里已解密的<b>明文 API Key</b>
 * （该类注释自述「持有的是已解密的明文 apiKey，仅存在于服务端内存」），
 * 以及 Redis 口令、数据库口令等一切运行时明文。<b>一次未授权下载 = 全部密钥泄漏。</b>
 * 因此本类与 {@code application.yml} 的 {@code management.endpoints.web.exposure} 是配套的
 * 两道收敛：这里管「谁能访问」，那里管「能访问到什么」。
 */
@Configuration
public class ActuatorSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest()
                        .access((authentication, context) ->
                                new AuthorizationDecision(isLoopback(context.getRequest()))));

        return http.build();
    }

    /**
     * 是否来自回环地址。
     * <p>
     * 三种写法都要认：IPv4 的 {@code 127.0.0.1}、IPv6 的 {@code ::1} 与其展开形式
     * {@code 0:0:0:0:0:0:0:1}。只判 {@code 127.0.0.1} 会在「Tomcat 以 IPv6 栈监听」时
     * 把合法请求全部拒掉 —— 那时表现为「运维连不上 actuator」，而不会表现为不安全，
     * 属于容易查错方向的故障。
     */
    private static boolean isLoopback(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return "127.0.0.1".equals(remote)
                || "::1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote);
    }
}
