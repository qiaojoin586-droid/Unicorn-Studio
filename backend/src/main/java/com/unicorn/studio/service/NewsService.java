package com.unicorn.studio.service;

import com.unicorn.studio.model.News;
import com.unicorn.studio.repository.NewsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class NewsService {

    private static final String PUBLISHED = "published";

    private final NewsRepository newsRepository;

    public NewsService(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    /** 公开列表：仅已发布新闻。 */
    public List<News> findPublished(String category) {
        if (category != null && !category.isBlank()) {
            return newsRepository.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category);
        }
        return newsRepository.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED);
    }

    /** 公开详情：按 slug 查询已发布新闻，并自增浏览量。 */
    @Transactional
    public Optional<News> findPublishedBySlug(String slug) {
        Optional<News> news = newsRepository.findBySlugAndStatus(slug, PUBLISHED);
        news.ifPresent(n -> {
            n.setViewCount(n.getViewCount() + 1);
            newsRepository.save(n);
        });
        return news;
    }
}
