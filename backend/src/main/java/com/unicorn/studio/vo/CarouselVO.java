package com.unicorn.studio.vo;

import com.unicorn.studio.model.Carousel;

/** 首页轮播图 VO：仅暴露展示所需字段，不含 status/排序/审计字段。 */
public record CarouselVO(
        Long id,
        String imageUrl,
        String title,
        String subtitle,
        String linkUrl) {

    public static CarouselVO from(Carousel carousel) {
        return new CarouselVO(
                carousel.getId(),
                carousel.getImageUrl(),
                carousel.getTitle(),
                carousel.getSubtitle(),
                carousel.getLinkUrl());
    }
}
