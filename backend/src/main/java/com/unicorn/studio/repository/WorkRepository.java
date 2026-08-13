package com.unicorn.studio.repository;

import com.unicorn.studio.model.Work;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkRepository extends JpaRepository<Work, Long> {

    List<Work> findByStatusOrderBySortOrderAscIdDesc(String status);

    List<Work> findByStatusAndFeaturedTrueOrderBySortOrderAscIdDesc(String status);

    List<Work> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category);

    Optional<Work> findBySlugAndStatus(String slug, String status);
}
