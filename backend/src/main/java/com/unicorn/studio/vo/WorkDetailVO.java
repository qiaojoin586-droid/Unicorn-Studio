package com.unicorn.studio.vo;

import com.unicorn.studio.common.WorkTags;
import com.unicorn.studio.model.Work;

import java.time.LocalDateTime;

/** 作品详情 VO：在卡片字段基础上增加正文、外链、浏览量与发布时间；不含 clientName 等后台字段。 */
public record WorkDetailVO(
        Long id,
        String slug,
        String title,
        String category,
        String coverUrl,
        String summary,
        String content,
        String externalLink,
        String icon,
        String coverClass,
        String tagType,
        String authors,
        String dateLabel,
        Integer viewCount,
        LocalDateTime publishedAt) {

    public static WorkDetailVO from(Work work) {
        return new WorkDetailVO(
                work.getId(),
                work.getSlug(),
                work.getTitle(),
                work.getCategory(),
                work.getCoverUrl(),
                work.getSummary(),
                work.getContent(),
                work.getExternalLink(),
                work.getIcon(),
                work.getCoverClass(),
                // 与 WorkCardVO 同一兜底口径：历史数据 tag_type 为 NULL 时按 category 还原
                WorkTags.resolve(work.getTagType(), work.getCategory()),
                work.getAuthors(),
                work.getDateLabel(),
                work.getViewCount(),
                work.getPublishedAt());
    }
}
