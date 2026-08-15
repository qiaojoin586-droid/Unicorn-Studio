package com.unicorn.studio.vo;

import com.unicorn.studio.model.Work;

/** 作品卡片 VO（首页推荐/列表展示用）：不含正文与后台字段（clientName 等）。 */
public record WorkCardVO(
        Long id,
        String slug,
        String title,
        String summary,
        String icon,
        String coverClass,
        String tagType,
        String authors,
        String dateLabel) {

    public static WorkCardVO from(Work work) {
        return new WorkCardVO(
                work.getId(),
                work.getSlug(),
                work.getTitle(),
                work.getSummary(),
                work.getIcon(),
                work.getCoverClass(),
                work.getTagType(),
                work.getAuthors(),
                work.getDateLabel());
    }
}
