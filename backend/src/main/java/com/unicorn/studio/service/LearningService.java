package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.mapper.LearningResourceMapper;
import com.unicorn.studio.model.LearningResource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LearningService {

    private final LearningResourceMapper repository;

    public LearningService(LearningResourceMapper repository) {
        this.repository = repository;
    }

    public List<LearningResource> findAll() {
        return repository.selectList(new LambdaQueryWrapper<>());
    }
}
