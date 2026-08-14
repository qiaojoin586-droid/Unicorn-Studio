package com.unicorn.studio.service;

import com.unicorn.studio.mapper.WorkMapper;
import com.unicorn.studio.model.Work;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WorkService {

    private static final String PUBLISHED = "published";

    private final WorkMapper workMapper;

    public WorkService(WorkMapper workMapper) {
        this.workMapper = workMapper;
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

    /** 公开详情：按 slug 查询已发布作品，并自增浏览量。 */
    @Transactional
    public Optional<Work> findPublishedBySlug(String slug) {
        Optional<Work> work = workMapper.findBySlugAndStatus(slug, PUBLISHED);
        work.ifPresent(w -> {
            w.setViewCount(w.getViewCount() + 1);
            workMapper.updateById(w);
        });
        return work;
    }
}
