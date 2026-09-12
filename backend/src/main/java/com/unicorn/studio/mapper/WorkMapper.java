package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.Work;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

public interface WorkMapper extends BaseMapper<Work> {

    /** 浏览量增量回写（§4.4）：增量 UPDATE，绝不用 updateById（update-strategy=ignored 会全列覆盖）。 */
    @Update("UPDATE work SET view_count = view_count + #{delta} WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id, @Param("delta") long delta);

    default Optional<Work> findBySlug(String slug) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Work>().eq(Work::getSlug, slug)));
    }

    default List<Work> findByStatusOrderBySortOrderAscIdDesc(String status) {
        return selectList(new LambdaQueryWrapper<Work>()
                .eq(Work::getStatus, status)
                .orderByDesc(Work::getSortOrder, Work::getId));
    }

    default List<Work> findByStatusAndFeaturedOrderBySortOrderAscIdDesc(String status, Integer featured) {
        return selectList(new LambdaQueryWrapper<Work>()
                .eq(Work::getStatus, status)
                .eq(Work::getFeatured, featured)
                .orderByDesc(Work::getSortOrder, Work::getId));
    }

    default List<Work> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category) {
        return selectList(new LambdaQueryWrapper<Work>()
                .eq(Work::getStatus, status)
                .eq(Work::getCategory, category)
                .orderByDesc(Work::getSortOrder, Work::getId));
    }

    default Optional<Work> findBySlugAndStatus(String slug, String status) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Work>()
                .eq(Work::getSlug, slug)
                .eq(Work::getStatus, status)));
    }
}
