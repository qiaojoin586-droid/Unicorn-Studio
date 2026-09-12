package com.unicorn.studio.service;

import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.common.cache.ViewCountService;
import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.model.News;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class NewsService {

    private static final String PUBLISHED = "published";

    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    private final NewsMapper newsMapper;
    private final CacheHelper cache;
    private final ViewCountService viewCountService;

    public NewsService(NewsMapper newsMapper, CacheHelper cache, ViewCountService viewCountService) {
        this.newsMapper = newsMapper;
        this.cache = cache;
        this.viewCountService = viewCountService;
    }

    /** 公开列表：仅已发布新闻。 */
    public List<News> findPublished(String category) {
        if (category != null && !category.isBlank()) {
            return newsMapper.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category);
        }
        return newsMapper.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED);
    }

    /**
     * 公开详情：按 slug 查询已发布新闻。
     * 穿透防护（§4.1 P1）+ 写热点剥离（§4.4）：浏览量 Redis INCR，不再 SELECT+全列 UPDATE
     * （NewsDetailVO 不回显 viewCount，仅做计数，由定时任务增量回写）。
     */
    public Optional<News> findPublishedBySlug(String slug) {
        if (cache.getString(CacheKeys.nullNews(slug)).isPresent()) {
            return Optional.empty();
        }
        Optional<News> news = newsMapper.findBySlugAndStatus(slug, PUBLISHED);
        if (news.isPresent()) {
            viewCountService.increment("news", news.get().getId());
        } else {
            cache.setString(CacheKeys.nullNews(slug), "__NULL__", NULL_TTL);
        }
        return news;
    }
}
