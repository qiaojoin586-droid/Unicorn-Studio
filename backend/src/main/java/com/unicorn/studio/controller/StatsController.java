package com.unicorn.studio.controller;

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
        statsService.recordPv(path, referer, httpRequest);
        return ApiResponse.ok();
    }
}
