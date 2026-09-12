package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.News;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface NewsMapper extends BaseMapper<News> {

    /** 浏览量增量回写（§4.4）：增量 UPDATE，绝不用 updateById。 */
    @Update("UPDATE news SET view_count = view_count + #{delta} WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id, @Param("delta") long delta);

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
