package com.unicorn.studio.common.log;

import com.unicorn.studio.common.IpUtils;
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
 *
 * <p>
 * <b>★ 访问日志用独立的 logger 名（{@code "ACCESS_LOG"}，与 {@code "ADMIN_AUDIT"} 同一套路）。</b>
 * 这一点是线上事故换来的：日志名一旦落在 {@code com.unicorn.studio.*} 命名空间里，
 * 就会被 {@code application-prod.yml} 的 {@code logging.level.com.unicorn.studio=WARN} 一并压掉 ——
 * 而访问日志打的是 <b>INFO</b>，于是服务器上「接口一条日志都没有」，
 * 看起来像「日志根本没落盘」，实际文件与 appender 全都正常，只是事件在 logger 层面就被丢弃了。
 * 独立的无点号名字 + {@code logging.level.ACCESS_LOG} 单独控制后，
 * 以后收紧业务包级别不会再误伤接口日志。
 * <p>
 * 异常堆栈仍走普通类 logger：prod 的 WARN 阈值不会拦掉 ERROR。
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    /** 访问日志专用 logger（见类注释：名字不能落在 com.unicorn.studio 下） */
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS_LOG");

    /** 常规 logger：只用于异常堆栈，保持与其它业务类一致 */
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
        ACCESS_LOG.info("[ACCESS] {} {}{} status={} ip={} {}ms",
                request.getMethod(),
                request.getRequestURI(),
                queryStringOf(request),
                response.getStatus(),
                IpUtils.getClientIp(request),
                duration);
    }

    private String queryStringOf(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? "" : "?" + query;
    }
}
