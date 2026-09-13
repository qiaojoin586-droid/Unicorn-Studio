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

    /**
     * 列表用列投影（B-09）：<b>不含 MEDIUMTEXT 的 {@code content}</b>。
     * <p>
     * {@code work.content} 是 MEDIUMTEXT（上限 16MB），而列表卡片 {@link com.unicorn.studio.vo.WorkCardVO}
     * 从一开始就不含它 —— 不投影的话，SQL 会把整列正文查出来、反序列化成实体字段，
     * 再在 {@code WorkCardVO.from()} 里被丢弃。这份开销是纯浪费，而且列表结果是要进
     * Redis 缓存的，缓存体积也跟着一起被放大。
     * <p>
     * 字段清单与 {@code WorkCardVO} 的记录分量一一对应；<b>改动 VO 时务必同步这里</b>，
     * 否则会拿到 null。注意 WHERE/ORDER BY 用到的列（status/featured/category/sortOrder/id）
     * 不需要出现在投影里。
     */
    default LambdaQueryWrapper<Work> cardProjection() {
        return new LambdaQueryWrapper<Work>()
                .select(Work::getId, Work::getSlug, Work::getTitle, Work::getCoverUrl,
                        Work::getSummary, Work::getIcon, Work::getCoverClass, Work::getTagType,
                        Work::getAuthors, Work::getDateLabel);
    }

    default List<Work> findByStatusOrderBySortOrderAscIdDesc(String status) {
        return selectList(cardProjection()
                .eq(Work::getStatus, status)
                .orderByDesc(Work::getSortOrder, Work::getId));
    }

    default List<Work> findByStatusAndFeaturedOrderBySortOrderAscIdDesc(String status, Integer featured) {
        return selectList(cardProjection()
                .eq(Work::getStatus, status)
                .eq(Work::getFeatured, featured)
                .orderByDesc(Work::getSortOrder, Work::getId));
    }

    default List<Work> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category) {
        return selectList(cardProjection()
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
