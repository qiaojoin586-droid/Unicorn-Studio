package com.unicorn.studio.controller;

import com.unicorn.studio.common.IpUtils;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 前台 PV 埋点（实施方案 §3.7 / §5.7）：忽略 DNT；IP 哈希；异步落库。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    public record PvRequest(String path, String referer) {
    }

    @PostMapping("/pv")
    public ApiResponse<Void> pv(@RequestBody(required = false) PvRequest request,
                                @RequestHeader(value = "DNT", required = false) String dnt,
                                HttpServletRequest httpRequest) {
        if (dnt != null && "1".equals(dnt)) {
            return ApiResponse.ok();
        }
        String path = request == null ? null : request.path();
        String referer = request == null ? null : request.referer();
        // B-01：在【请求线程内】把异步方法要用到的字段全部取出，只传值。
        // 不能把 httpRequest 直接交给 @Async 方法 —— 届时容器线程已归还，Tomcat 会回收并复用
        // 该 request 对象，异步线程再解引用会读到下一个请求的数据或直接抛异常。
        statsService.recordPv(path, referer,
                httpRequest.getHeader("User-Agent"),
                IpUtils.getClientIp(httpRequest));
        return ApiResponse.ok();
    }
}
