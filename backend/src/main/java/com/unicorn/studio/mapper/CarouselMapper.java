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

    /**
     * 按绑定页面取启用项（V11）。
     * 背景类页面（about/work-detail）取返回列表首条即单条生效背景。
     */
    default List<Carousel> findByPageKeyAndStatusOrderBySortOrderAscIdAsc(String pageKey, Integer status) {
        return selectList(new LambdaQueryWrapper<Carousel>()
                .eq(Carousel::getPageKey, pageKey)
                .eq(Carousel::getStatus, status)
                .orderByAsc(Carousel::getSortOrder, Carousel::getId));
    }
}
