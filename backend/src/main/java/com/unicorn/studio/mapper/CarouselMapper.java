package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.Carousel;

import java.util.List;

public interface CarouselMapper extends BaseMapper<Carousel> {

    default List<Carousel> findByStatusOrderBySortOrderAscIdAsc(Integer status) {
        return selectList(new LambdaQueryWrapper<Carousel>()
                .eq(Carousel::getStatus, status)
                .orderByAsc(Carousel::getSortOrder, Carousel::getId));
    }
}
