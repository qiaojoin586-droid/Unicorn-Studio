package com.unicorn.studio.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.unicorn.studio.model.LearningResource;

import java.time.LocalDateTime;

/**
 * 学习资料列表卡片 VO（B5）。
 * <p>
 * 刻意不含富文本正文 {@code content}（MEDIUMTEXT）——列表接口避免过度返回，
 * 缓存也不放大字段（§5.5.2 规则 1「缓存 VO 而不是实体，不缓存 content」）。
 * 详情页正文仍由 {@code /api/learning/resources/{id}} 返回完整实体。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LearningCardVO(
        Long id,
        String title,
        String description,
        String url,
        String category,
        String tags,
        String coverUrl,
        String attachmentUrl,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static LearningCardVO from(LearningResource r) {
        return new LearningCardVO(
                r.getId(),
                r.getTitle(),
                r.getDescription(),
                r.getUrl(),
                r.getCategory(),
                r.getTags(),
                r.getCoverUrl(),
                r.getAttachmentUrl(),
                r.getSortOrder(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
