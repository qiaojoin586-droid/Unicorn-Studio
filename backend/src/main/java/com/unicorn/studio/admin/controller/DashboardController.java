package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.DashboardService;
import com.unicorn.studio.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 仪表盘（实施方案 §3.2）。 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('dashboard:view')")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(dashboardService.summary(days));
    }
}
