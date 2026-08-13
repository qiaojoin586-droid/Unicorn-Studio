package com.unicorn.studio.repository;

import com.unicorn.studio.model.LearningResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface LearningResourceRepository extends JpaRepository<LearningResource, Long>, JpaSpecificationExecutor<LearningResource> {

    List<LearningResource> findByStatusOrderBySortOrderAscIdAsc(Integer status);
}
