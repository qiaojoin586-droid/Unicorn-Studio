package com.unicorn.studio.service;

import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.common.cache.ViewCountService;
import com.unicorn.studio.mapper.WorkMapper;
import com.unicorn.studio.model.Work;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class WorkService {

    private static final String PUBLISHED = "published";

    /** 空值哨兵 TTL（§4.1 P1）：显著短于正常 TTL，且必须短于「新建作品应尽快可见」的期望 */
    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    private final WorkMapper workMapper;
    private final CacheHelper cache;
    private final ViewCountService viewCountService;

    public WorkService(WorkMapper workMapper, CacheHelper cache, ViewCountService viewCountService) {
        this.workMapper = workMapper;
        this.cache = cache;
        this.viewCountService = viewCountService;
    }

    /** 公开列表：仅已发布作品，按排序倒序 id（新作品在前）。 */
    public List<Work> findPublished(String category, Integer featured) {
        if (Integer.valueOf(1).equals(featured)) {
            return workMapper.findByStatusAndFeaturedOrderBySortOrderAscIdDesc(PUBLISHED, 1);
        }
        if (category != null && !category.isBlank()) {
            return workMapper.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category);
        }
        return workMapper.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED);
    }

    /**
     * 公开详情：按 slug 查询已发布作品。
     * <p>
     * 穿透防护（§4.1 P1）：命中空值哨兵直接空；查库确认不存在则写哨兵。
     * 写热点剥离（§4.4）：浏览量改为 Redis {@code INCR}，不再 SELECT+全列 UPDATE；
     * 返回的 viewCount = 库基准 + 未回写增量，定时任务统一增量回写。
     */
    public Optional<Work> findPublishedBySlug(String slug) {
        if (cache.getString(CacheKeys.nullWork(slug)).isPresent()) {
            return Optional.empty();
        }
        Optional<Work> work = workMapper.findBySlugAndStatus(slug, PUBLISHED);
        if (work.isPresent()) {
            Work w = work.get();
            long delta = viewCountService.increment("work", w.getId());
            w.setViewCount(w.getViewCount() + (int) delta);
        } else {
            cache.setString(CacheKeys.nullWork(slug), "__NULL__", NULL_TTL);
        }
        return work;
    }
}
