package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.mapper.LearningResourceMapper;
import com.unicorn.studio.model.LearningResource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class LearningService {

    private final LearningResourceMapper repository;

    public LearningService(LearningResourceMapper repository) {
        this.repository = repository;
    }

    /** 公开列表：仅上架资料，按排序值升序。 */
    public List<LearningResource> findAll() {
        return repository.selectList(new LambdaQueryWrapper<LearningResource>()
                .eq(LearningResource::getStatus, 1)
                .orderByAsc(LearningResource::getSortOrder, LearningResource::getId));
    }

    /** 公开详情：按 id 查询上架资料。 */
    public Optional<LearningResource> findPublishedById(Long id) {
        return Optional.ofNullable(repository.selectOne(new LambdaQueryWrapper<LearningResource>()
                .eq(LearningResource::getId, id)
                .eq(LearningResource::getStatus, 1)));
    }
}
