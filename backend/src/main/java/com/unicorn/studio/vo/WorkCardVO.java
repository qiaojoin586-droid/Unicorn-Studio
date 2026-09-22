package com.unicorn.studio.vo;

import com.unicorn.studio.common.WorkTags;
import com.unicorn.studio.model.Work;

/** 作品卡片 VO（首页推荐/列表展示用）：不含正文与后台字段（clientName 等）。 */
public record WorkCardVO(
        Long id,
        String slug,
        String title,
        String coverUrl,
        String summary,
        String icon,
        String coverClass,
        String tagType,
        String authors,
        String dateLabel) {

    /**
     * {@code tagType} 走 {@link WorkTags#resolve} 兜底：卡片 VO 不对外暴露 {@code category}，
     * 但投影里取到了它，因此这里能把「写入路径修复前 tag_type 为 NULL」的历史数据
     * 按分类还原成正确的徽章枚举，前台无需再猜默认值。
     */
    public static WorkCardVO from(Work work) {
        return new WorkCardVO(
                work.getId(),
                work.getSlug(),
                work.getTitle(),
                work.getCoverUrl(),
                work.getSummary(),
                work.getIcon(),
                work.getCoverClass(),
                WorkTags.resolve(work.getTagType(), work.getCategory()),
                work.getAuthors(),
                work.getDateLabel());
    }
}
