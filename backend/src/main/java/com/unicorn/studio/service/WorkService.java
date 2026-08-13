package com.unicorn.studio.service;

import com.unicorn.studio.model.Work;
import com.unicorn.studio.repository.WorkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WorkService {

    private static final String PUBLISHED = "published";

    private final WorkRepository workRepository;

    public WorkService(WorkRepository workRepository) {
        this.workRepository = workRepository;
    }

    /** 公开列表：仅已发布作品，按排序倒序 id（新作品在前）。 */
    public List<Work> findPublished(String category, Boolean featured) {
        if (Boolean.TRUE.equals(featured)) {
            return workRepository.findByStatusAndFeaturedTrueOrderBySortOrderAscIdDesc(PUBLISHED);
        }
        if (category != null && !category.isBlank()) {
            return workRepository.findByStatusAndCategoryOrderBySortOrderAscIdDesc(PUBLISHED, category);
        }
        return workRepository.findByStatusOrderBySortOrderAscIdDesc(PUBLISHED);
    }

    /** 公开详情：按 slug 查询已发布作品，并自增浏览量。 */
    @Transactional
    public Optional<Work> findPublishedBySlug(String slug) {
        Optional<Work> work = workRepository.findBySlugAndStatus(slug, PUBLISHED);
        work.ifPresent(w -> {
            w.setViewCount(w.getViewCount() + 1);
            workRepository.save(w);
        });
        return work;
    }
}
