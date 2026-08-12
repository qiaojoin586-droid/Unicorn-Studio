package com.unicorn.studio.service;

import com.unicorn.studio.model.LearningResource;
import com.unicorn.studio.repository.LearningResourceRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LearningService {

    private final LearningResourceRepository repository;

    public LearningService(LearningResourceRepository repository) {
        this.repository = repository;
    }

    public List<LearningResource> findAll() {
        return repository.findAll();
    }
}
