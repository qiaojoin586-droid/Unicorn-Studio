package com.unicorn.studio.controller;

import com.unicorn.studio.common.SlugValidator;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.LearningResource;
import com.unicorn.studio.service.LearningService;
import com.unicorn.studio.vo.LearningCardVO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public ApiResponse<Map<String, List<LearningCardVO>>> getResources() {
        List<LearningCardVO> resources = learningService.findAll();
        return ApiResponse.ok(Map.of("resources", resources));
    }

    @GetMapping("/resources/{id}")
    public ApiResponse<Map<String, LearningResource>> detail(@PathVariable Long id) {
        // 穿透防护（§4.1 P2）：id ≤ 0 直接 404，不打库
        if (!SlugValidator.isPositiveId(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "学习资料不存在或已下线");
        }
        LearningResource resource = learningService.findPublishedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "学习资料不存在或已下线"));
        return ApiResponse.ok(Map.of("resource", resource));
    }
}
