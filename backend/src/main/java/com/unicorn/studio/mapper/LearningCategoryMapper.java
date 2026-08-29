package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.LearningCategory;

import java.util.List;
import java.util.Optional;

public interface LearningCategoryMapper extends BaseMapper<LearningCategory> {

    default Optional<LearningCategory> findByName(String name) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<LearningCategory>()
                .eq(LearningCategory::getName, name)));
    }

    default List<LearningCategory> findAllEnabled() {
        return selectList(new LambdaQueryWrapper<LearningCategory>()
                .eq(LearningCategory::getStatus, 1)
                .orderByAsc(LearningCategory::getSortOrder, LearningCategory::getId));
    }
}
