package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, Boolean>> health() {
        return ApiResponse.ok(Map.of("ok", true));
    }
}
