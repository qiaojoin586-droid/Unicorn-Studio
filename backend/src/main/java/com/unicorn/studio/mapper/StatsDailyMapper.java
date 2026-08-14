package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.StatsDaily;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StatsDailyMapper extends BaseMapper<StatsDaily> {

    default Optional<StatsDaily> findByStatDate(LocalDate date) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<StatsDaily>().eq(StatsDaily::getStatDate, date)));
    }

    default List<StatsDaily> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to) {
        return selectList(new LambdaQueryWrapper<StatsDaily>()
                .ge(StatsDaily::getStatDate, from)
                .le(StatsDaily::getStatDate, to)
                .orderByAsc(StatsDaily::getStatDate));
    }
}
