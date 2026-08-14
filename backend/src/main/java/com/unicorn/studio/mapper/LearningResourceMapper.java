package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.LearningResource;

import java.util.List;

public interface LearningResourceMapper extends BaseMapper<LearningResource> {

    default List<LearningResource> findByStatusOrderBySortOrderAscIdAsc(Integer status) {
        return selectList(new LambdaQueryWrapper<LearningResource>()
                .eq(LearningResource::getStatus, status)
                .orderByAsc(LearningResource::getSortOrder, LearningResource::getId));
    }
}
