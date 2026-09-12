package com.unicorn.studio.controller;

import com.unicorn.studio.common.SlugValidator;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.News;
import com.unicorn.studio.service.NewsService;
import com.unicorn.studio.vo.NewsCardVO;
import com.unicorn.studio.vo.NewsDetailVO;
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
    public ApiResponse<Map<String, List<NewsCardVO>>> list(@RequestParam(required = false) String category) {
        List<NewsCardVO> items = newsService.findPublished(category).stream()
                .map(NewsCardVO::from)
                .toList();
        return ApiResponse.ok(Map.of("list", items));
    }

    @GetMapping("/{slug}")
    public ApiResponse<Map<String, NewsDetailVO>> detail(@PathVariable String slug) {
        if (!SlugValidator.isValidSlug(slug)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "新闻不存在或已下线");
        }
        News news = newsService.findPublishedBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "新闻不存在或已下线"));
        return ApiResponse.ok(Map.of("news", NewsDetailVO.from(news)));
    }
}
