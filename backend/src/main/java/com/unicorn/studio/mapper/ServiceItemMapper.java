package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.ServiceItem;

import java.util.List;

public interface ServiceItemMapper extends BaseMapper<ServiceItem> {

    default List<ServiceItem> findByStatusOrderBySortOrderAscIdAsc(String status) {
        return selectList(new LambdaQueryWrapper<ServiceItem>()
                .eq(ServiceItem::getStatus, status)
                .orderByAsc(ServiceItem::getSortOrder, ServiceItem::getId));
    }
}
