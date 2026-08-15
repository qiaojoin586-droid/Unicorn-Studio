package com.unicorn.studio.vo;

import com.unicorn.studio.model.News;

import java.time.LocalDateTime;

/** 新闻卡片 VO（列表展示用）：不含正文 content 与后台字段，避免列表接口过度返回。 */
public record NewsCardVO(
        Long id,
        String slug,
        String title,
        String category,
        String coverUrl,
        String summary,
        LocalDateTime publishedAt) {

    public static NewsCardVO from(News news) {
        return new NewsCardVO(
                news.getId(),
                news.getSlug(),
                news.getTitle(),
                news.getCategory(),
                news.getCoverUrl(),
                news.getSummary(),
                news.getPublishedAt());
    }
}
