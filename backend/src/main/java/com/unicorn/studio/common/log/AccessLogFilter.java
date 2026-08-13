package com.unicorn.studio.common.log;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 接口访问日志：每次 /api/** 调用在控制台与日志文件打印
 * 方法、路径、状态码、客户端 IP 与耗时；处理异常时打印完整堆栈。
 * SSE 等异步请求在真正完成时才记录（AsyncListener），耗时统计准确。
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            // 出错详情（含堆栈）落日志，便于定位
            log.error("接口处理异常: {} {}{}", request.getMethod(),
                    request.getRequestURI(), queryStringOf(request), e);
            throw e;
        }

        if (request.isAsyncStarted()) {
            // 异步请求（如 SSE 流式）：完成/超时/出错时再记录，耗时才是真实值
            AsyncContext asyncContext = request.getAsyncContext();
            asyncContext.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    logRequest(request, response, start);
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    logRequest(request, response, start);
                }

                @Override
                public void onError(AsyncEvent event) {
                    logRequest(request, response, start);
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // 无需处理
                }
            });
        } else {
            logRequest(request, response, start);
        }
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response, long start) {
        long duration = System.currentTimeMillis() - start;
        log.info("[ACCESS] {} {}{} status={} ip={} {}ms",
                request.getMethod(),
                request.getRequestURI(),
                queryStringOf(request),
                response.getStatus(),
                resolveIp(request),
                duration);
    }

    private String queryStringOf(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? "" : "?" + query;
    }

    /** 经代理部署时取 X-Forwarded-For 首个地址，否则取直连地址。 */
    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
