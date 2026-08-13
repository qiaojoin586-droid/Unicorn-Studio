package com.unicorn.studio.repository;

import com.unicorn.studio.model.Work;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface WorkRepository extends JpaRepository<Work, Long>, JpaSpecificationExecutor<Work> {

    Optional<Work> findBySlug(String slug);

    List<Work> findByStatusOrderBySortOrderAscIdDesc(String status);

    List<Work> findByStatusAndFeaturedOrderBySortOrderAscIdDesc(String status, Integer featured);

    List<Work> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category);

    Optional<Work> findBySlugAndStatus(String slug, String status);
}
