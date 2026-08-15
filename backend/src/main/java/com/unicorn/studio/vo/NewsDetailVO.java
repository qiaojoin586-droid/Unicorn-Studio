package com.unicorn.studio.vo;

import com.unicorn.studio.model.News;

import java.time.LocalDateTime;

/** 新闻详情 VO：在卡片字段基础上增加正文 content。 */
public record NewsDetailVO(
        Long id,
        String slug,
        String title,
        String category,
        String coverUrl,
        String summary,
        String content,
        LocalDateTime publishedAt) {

    public static NewsDetailVO from(News news) {
        return new NewsDetailVO(
                news.getId(),
                news.getSlug(),
                news.getTitle(),
                news.getCategory(),
                news.getCoverUrl(),
                news.getSummary(),
                news.getContent(),
                news.getPublishedAt());
    }
}
