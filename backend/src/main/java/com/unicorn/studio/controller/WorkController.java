package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Work;
import com.unicorn.studio.service.WorkService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/works")
public class WorkController {

    private final WorkService workService;

    public WorkController(WorkService workService) {
        this.workService = workService;
    }

    @GetMapping
    public ApiResponse<Map<String, List<Work>>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean featured) {
        Integer featuredFlag = Boolean.TRUE.equals(featured) ? 1 : null;
        return ApiResponse.ok(Map.of("list", workService.findPublished(category, featuredFlag)));
    }

    @GetMapping("/{slug}")
    public ApiResponse<Map<String, Work>> detail(@PathVariable String slug) {
        Work work = workService.findPublishedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "作品不存在或已下线"));
        return ApiResponse.ok(Map.of("work", work));
    }
}
