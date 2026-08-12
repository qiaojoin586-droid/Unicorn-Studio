package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.LearningResource;
import com.unicorn.studio.service.LearningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private final LearningService learningService;

    public LearningController(LearningService learningService) {
        this.learningService = learningService;
    }

    @GetMapping("/resources")
    public ApiResponse<Map<String, List<LearningResource>>> getResources() {
        List<LearningResource> resources = learningService.findAll();
        return ApiResponse.ok(Map.of("resources", resources));
    }
}
