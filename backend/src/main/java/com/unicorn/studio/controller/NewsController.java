package com.unicorn.studio.controller;

import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.News;
import com.unicorn.studio.service.NewsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 前台新闻接口（实施方案 §3.7）。 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsService newsService;

    public NewsController(NewsService newsService) {
        this.newsService = newsService;
    }

    @GetMapping
    public ApiResponse<Map<String, List<News>>> list(@RequestParam(required = false) String category) {
        return ApiResponse.ok(Map.of("list", newsService.findPublished(category)));
    }

    @GetMapping("/{slug}")
    public ApiResponse<Map<String, News>> detail(@PathVariable String slug) {
        News news = newsService.findPublishedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "新闻不存在或已下线"));
        return ApiResponse.ok(Map.of("news", news));
    }
}
