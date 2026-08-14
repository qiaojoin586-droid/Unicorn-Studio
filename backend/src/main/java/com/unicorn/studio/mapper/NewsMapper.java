package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.News;

import java.util.List;
import java.util.Optional;

public interface NewsMapper extends BaseMapper<News> {

    default Optional<News> findBySlug(String slug) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<News>().eq(News::getSlug, slug)));
    }

    default List<News> findByStatusOrderBySortOrderAscIdDesc(String status) {
        return selectList(new LambdaQueryWrapper<News>()
                .eq(News::getStatus, status)
                .orderByDesc(News::getSortOrder, News::getId));
    }

    default List<News> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category) {
        return selectList(new LambdaQueryWrapper<News>()
                .eq(News::getStatus, status)
                .eq(News::getCategory, category)
                .orderByDesc(News::getSortOrder, News::getId));
    }

    default Optional<News> findBySlugAndStatus(String slug, String status) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<News>()
                .eq(News::getSlug, slug)
                .eq(News::getStatus, status)));
    }
}
