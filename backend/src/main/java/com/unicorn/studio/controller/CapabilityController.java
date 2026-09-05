package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.service.CapabilityService;
import com.unicorn.studio.vo.CapabilityVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capabilities")
public class CapabilityController {

    private final CapabilityService capabilityService;

    public CapabilityController(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping
    public ApiResponse<Map<String, List<CapabilityVO>>> list() {
        List<CapabilityVO> items = capabilityService.findPublished().stream()
                .map(CapabilityVO::from)
                .toList();
        return ApiResponse.ok(Map.of("list", items));
    }
}
