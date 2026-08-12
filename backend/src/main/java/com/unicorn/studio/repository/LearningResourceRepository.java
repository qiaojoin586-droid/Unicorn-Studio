package com.unicorn.studio.repository;

import com.unicorn.studio.model.LearningResource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningResourceRepository extends JpaRepository<LearningResource, Long> {
}
