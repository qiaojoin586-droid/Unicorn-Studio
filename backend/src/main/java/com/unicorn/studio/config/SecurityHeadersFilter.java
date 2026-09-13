package com.unicorn.studio.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态资源安全响应头（S-8）：给 {@code /uploads/**} 与 {@code /assets/**} 补
 * {@code X-Content-Type-Options: nosniff}。
 * <p>
 * <b>为什么需要</b>：{@code /uploads/**} 提供的是<b>用户上传的内容</b>。{@code nosniff} 禁止浏览器
 * 在 {@code Content-Type} 与内容不符时自行嗅探类型 —— 这是这类场景的标准兜底。
 * 本项目上传白名单很紧（html/svg/js 均不在内）、存储名是 UUID，所以风险面本来就小；
 * 但这一层成本为零，属于纵深防御该有的最后一环。
 * <p>
 * <b>为什么要补这一处</b>：前端的 Node 生产服务（{@code frontend/server/index.js}）早已设置
 * nosniff / X-Frame-Options / CSP 等一整组安全头，而后端这条 serving 路径<b>完全没有</b> ——
 * 同一份上传文件经不同路径访问会得到不同的安全基线，这本身就不该存在。
 * <p>
 * 实现放在 Filter 而不是 {@code addResourceHandlers}：{@code ResourceHandlerRegistration}
 * 不支持自定义响应头，且 Filter 对「经任何 Handler 命中」的请求都生效，覆盖面更完整。
 * 仅对静态资源路径生效，避免给业务 JSON 响应增加无谓的头。
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/uploads/") && !uri.startsWith("/assets/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        filterChain.doFilter(request, response);
    }
}
