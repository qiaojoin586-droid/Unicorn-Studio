package com.unicorn.studio.repository;

import com.unicorn.studio.model.News;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface NewsRepository extends JpaRepository<News, Long>, JpaSpecificationExecutor<News> {

    Optional<News> findBySlug(String slug);

    List<News> findByStatusOrderBySortOrderAscIdDesc(String status);

    List<News> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category);

    Optional<News> findBySlugAndStatus(String slug, String status);
}
