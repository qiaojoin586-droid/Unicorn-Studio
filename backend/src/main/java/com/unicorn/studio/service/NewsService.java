package com.unicorn.studio.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.common.cache.ViewCountService;
import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.model.News;
import com.unicorn.studio.vo.NewsCardVO;
import com.unicorn.studio.vo.NewsDetailVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class NewsService {

    private static final String PUBLISHED = "published";

    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    /** 列表 TTL：与作品列表同口径（30 min） */
    private static final Duration LIST_TTL = Duration.ofMinutes(30);

    /**
     * 详情 TTL 为 60 分钟。
     * <p>
     * 与作品详情不同，{@link NewsDetailVO} <b>不含 viewCount</b>（浏览量只做计数、不回显），
     * 因此这里可以放心用长 TTL —— 不存在「浏览量被缓存冻结」的问题。
     */
    private static final Duration DETAIL_TTL = Duration.ofMinutes(60);

    /** 分类维度的键长上限：超长分类不缓存 */
    private static final int MAX_CATEGORY_KEY_LENGTH = 64;

    private static final JavaType CARD_LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, NewsCardVO.class);

    private final NewsMapper newsMapper;
    private final CacheHelper cache;
    private final ViewCountService viewCountService;

    public NewsService(NewsMapper newsMapper, CacheHelper cache, ViewCountService viewCountService) {
        this.newsMapper = newsMapper;
        this.cache = cache;
        this.viewCountService = viewCountService;
    }

    /**
     * 公开列表：仅已发布新闻；Redis 缓存 30 min（按分类分键）。
     * <p>
     * 缓存 {@link NewsCardVO} 而非实体：与 `NewsMapper.cardProjection()` 的投影口径一致，
     * 列表结果序列化后即为响应体，缓存体积与响应体同量级。
     */
    public List<NewsCardVO> findPublished(String category) {
        String dimension = dimension(category);
        if (dimension == null) {
            return loadPublished(category);
        }
        String key = CacheKeys.newsList(dimension);
        return cache.<List<NewsCardVO>>get(key, CARD_LIST_TYPE).orElseGet(() -> {
            List<NewsCardVO> list = loadPublished(category);
            cache.set(key, list, LIST_TTL);
            return list;
        });
    }

    private List<NewsCardVO> loadPublished(String category) {
        if (category != null && !category.isBlank()) {
            // 与 dimension() 保持同一份归一化，避免「键相同、查询不同」
            return newsMapper.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category.trim())
                    .stream()
                    .map(NewsCardVO::from)
                    .toList();
        }
        return newsMapper.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED)
                .stream()
                .map(NewsCardVO::from)
                .toList();
    }

    /** @return null 表示该入参不参与缓存（分类过长） */
    private static String dimension(String category) {
        if (category == null || category.isBlank()) {
            return "all";
        }
        String trimmed = category.trim();
        return trimmed.length() > MAX_CATEGORY_KEY_LENGTH ? null : "cat-" + trimmed;
    }

    /**
     * 公开详情：按 slug 查询已发布新闻；Redis 缓存 60 min。
     * <p>
     * 穿透防护（§4.1 P1）+ 写热点剥离（§4.4）：浏览量 Redis INCR，不再 SELECT+全列 UPDATE。
     * 缓存命中时同样保留 INCR —— 缓存省的是读库，不是浏览统计。
     */
    public Optional<NewsDetailVO> findPublishedBySlug(String slug) {
        if (cache.getString(CacheKeys.nullNews(slug)).isPresent()) {
            return Optional.empty();
        }
        String key = CacheKeys.newsDetail(slug);
        Optional<NewsDetailVO> cached = cache.get(key, NewsDetailVO.class);
        if (cached.isPresent()) {
            viewCountService.increment("news", cached.get().id());
            return cached;
        }
        Optional<News> news = newsMapper.findBySlugAndStatus(slug, PUBLISHED);
        if (news.isEmpty()) {
            cache.setString(CacheKeys.nullNews(slug), "__NULL__", NULL_TTL);
            return Optional.empty();
        }
        News n = news.get();
        viewCountService.increment("news", n.getId());
        NewsDetailVO vo = NewsDetailVO.from(n);
        cache.set(key, vo, DETAIL_TTL);
        return Optional.of(vo);
    }
}
