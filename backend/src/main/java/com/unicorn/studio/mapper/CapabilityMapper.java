package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.Capability;

import java.util.List;

public interface CapabilityMapper extends BaseMapper<Capability> {

    default List<Capability> findByStatusOrderBySortOrderAscIdAsc(String status) {
        return selectList(new LambdaQueryWrapper<Capability>()
                .eq(Capability::getStatus, status)
                .orderByAsc(Capability::getSortOrder, Capability::getId));
    }
}
