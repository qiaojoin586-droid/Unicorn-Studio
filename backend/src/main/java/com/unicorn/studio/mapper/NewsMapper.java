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

    /**
     * 列表用列投影（B-09）：<b>不含 MEDIUMTEXT 的 {@code content}</b>。
     * <p>
     * 与 {@link WorkMapper#cardProjection()} 同一考量：列表结果只用于组装
     * {@link com.unicorn.studio.vo.NewsCardVO}（不含正文），若不投影，SQL 会把整列正文
     * 读出来再丢掉，同时放大 Redis 里的列表缓存体积。
     * 字段清单与 {@code NewsCardVO} 的记录分量一一对应，<b>改 VO 时务必同步这里</b>。
     */
    default LambdaQueryWrapper<News> cardProjection() {
        return new LambdaQueryWrapper<News>()
                .select(News::getId, News::getSlug, News::getTitle, News::getCategory,
                        News::getCoverUrl, News::getSummary, News::getPublishedAt);
    }

    default List<News> findByStatusOrderBySortOrderAscIdDesc(String status) {
        return selectList(cardProjection()
                .eq(News::getStatus, status)
                .orderByDesc(News::getSortOrder, News::getId));
    }

    default List<News> findByStatusAndCategoryOrderBySortOrderAscIdDesc(String status, String category) {
        return selectList(cardProjection()
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
