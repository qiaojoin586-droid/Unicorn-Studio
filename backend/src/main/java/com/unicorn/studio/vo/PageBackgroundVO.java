package com.unicorn.studio.vo;

import com.unicorn.studio.model.Carousel;

/**
 * 页面背景 VO（V11）：前台「关于我们」「作品详情」页所用背景配置。
 * <p>
 * enabled=false 时前端不渲染任何背景资源，保持页面原有默认样式（纯 CSS 渐变），
 * 因此「未配置」与「接口失败」都不会造成空白或布局跳动。
 */
public record PageBackgroundVO(
        boolean enabled,
        String mediaType,
        String imageUrl,
        String videoUrl) {

    private static final PageBackgroundVO NONE = new PageBackgroundVO(false, null, null, null);

    public static PageBackgroundVO none() {
        return NONE;
    }

    public static PageBackgroundVO from(Carousel carousel) {
        boolean isVideo = "video".equalsIgnoreCase(carousel.getMediaType());
        if (isVideo) {
            // 动态壁纸需要「视频 + 封面图」齐备：封面用于首屏秒出（poster），缺失则降级
            if (isBlank(carousel.getVideoUrl())) {
                return isBlank(carousel.getImageUrl())
                        ? none()
                        : new PageBackgroundVO(true, "image", carousel.getImageUrl(), null);
            }
            if (isBlank(carousel.getImageUrl())) {
                // 无封面则不作动态壁纸渲染（避免首屏直接等视频），退化为无背景
                return none();
            }
            return new PageBackgroundVO(true, "video", carousel.getImageUrl(), carousel.getVideoUrl());
        }
        return isBlank(carousel.getImageUrl())
                ? none()
                : new PageBackgroundVO(true, "image", carousel.getImageUrl(), null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
