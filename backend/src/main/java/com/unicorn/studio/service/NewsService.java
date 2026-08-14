package com.unicorn.studio.service;

import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.model.News;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class NewsService {

    private static final String PUBLISHED = "published";

    private final NewsMapper newsMapper;

    public NewsService(NewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    /** 公开列表：仅已发布新闻。 */
    public List<News> findPublished(String category) {
        if (category != null && !category.isBlank()) {
            return newsMapper.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category);
        }
        return newsMapper.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED);
    }

    /** 公开详情：按 slug 查询已发布新闻，并自增浏览量。 */
    @Transactional
    public Optional<News> findPublishedBySlug(String slug) {
        Optional<News> news = newsMapper.findBySlugAndStatus(slug, PUBLISHED);
        news.ifPresent(n -> {
            n.setViewCount(n.getViewCount() + 1);
            newsMapper.updateById(n);
        });
        return news;
    }
}
