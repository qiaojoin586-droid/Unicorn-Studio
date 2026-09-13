package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.Carousel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 轮播图管理（实施方案 §3.3）。
 * <p>
 * V11 起同一张表承载两类用途，由 {@code pageKey} 区分：
 * - {@code home}：首页 Hero 轮播（多张，原有语义不变）；
 * - {@code about} / {@code work-detail}：单背景页面，每页只生效一条启用项。
 */
@Service
public class CarouselAdminService {

    private static final Set<String> PAGE_KEYS = Set.of("home", "about", "work-detail", "works");
    /** 单背景页面：每页只应有一条启用背景 */
    private static final Set<String> BACKGROUND_PAGES = Set.of("about", "work-detail", "works");
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video");

    private final CarouselMapper carouselMapper;
    private final CacheHelper cache;

    public CarouselAdminService(CarouselMapper carouselMapper, CacheHelper cache) {
        this.carouselMapper = carouselMapper;
        this.cache = cache;
    }

    /**
     * @param group 可选分组过滤：{@code home}=首页轮播，{@code background}=页面背景（about+work-detail+works）；
     *              为 null 时不加过滤，保持原有全量列表行为。
     */
    public PageResult<Carousel> list(int page, int size, String group) {
        LambdaQueryWrapper<Carousel> wrapper = new LambdaQueryWrapper<>();
        if ("background".equals(group)) {
            // 背景页按页面分组展示，便于管理
            wrapper.in(Carousel::getPageKey, BACKGROUND_PAGES)
                    .orderByAsc(Carousel::getPageKey, Carousel::getSortOrder, Carousel::getId);
        } else {
            if ("home".equals(group)) {
                wrapper.eq(Carousel::getPageKey, "home");
            }
            wrapper.orderByAsc(Carousel::getSortOrder, Carousel::getId);
        }
        Page<Carousel> result = carouselMapper.selectPage(PageResult.safePage(page + 1, size), wrapper);
        return PageResult.of(result);
    }

    @Transactional
    public Carousel create(CarouselForm form) {
        if (form.imageUrl() == null || form.imageUrl().isBlank()) {
            throw BusinessException.badRequest("图片地址不能为空");
        }
        String pageKey = normalizePageKey(form.pageKey());
        String mediaType = normalizeMediaType(form.mediaType());
        String videoUrl = null;
        if ("video".equals(mediaType)) {
            if (form.videoUrl() == null || form.videoUrl().isBlank()) {
                throw BusinessException.badRequest("动态壁纸必须上传视频");
            }
            videoUrl = form.videoUrl();
        }
        Carousel carousel = Carousel.builder()
                .imageUrl(form.imageUrl())
                .title(form.title())
                .subtitle(form.subtitle())
                .linkUrl(form.linkUrl())
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(Boolean.TRUE.equals(form.enabled()) ? 1 : 0)
                .pageKey(pageKey)
                .mediaType(mediaType)
                .videoUrl(videoUrl)
                .build();
        carouselMapper.insert(carousel);
        disableSiblingBackgrounds(carousel);
        evictCarouselCache();
        return carousel;
    }

    @Transactional
    public void update(Long id, CarouselForm form) {
        Carousel carousel = getOrThrow(id);
        if (form.imageUrl() != null) {
            carousel.setImageUrl(form.imageUrl());
        }
        if (form.title() != null) {
            carousel.setTitle(form.title());
        }
        if (form.subtitle() != null) {
            carousel.setSubtitle(form.subtitle());
        }
        if (form.linkUrl() != null) {
            carousel.setLinkUrl(form.linkUrl());
        }
        if (form.sortOrder() != null) {
            carousel.setSortOrder(form.sortOrder());
        }
        if (form.enabled() != null) {
            carousel.setStatus(form.enabled() ? 1 : 0);
        }
        if (form.pageKey() != null) {
            carousel.setPageKey(normalizePageKey(form.pageKey()));
        }
        if (form.mediaType() != null) {
            carousel.setMediaType(normalizeMediaType(form.mediaType()));
        }
        if (form.videoUrl() != null) {
            carousel.setVideoUrl(form.videoUrl().isBlank() ? null : form.videoUrl());
        }
        // 类型收敛：静态壁纸不保留视频地址；动态壁纸必须有视频与封面
        if (!"video".equals(carousel.getMediaType())) {
            carousel.setVideoUrl(null);
        } else if (isBlank(carousel.getVideoUrl())) {
            throw BusinessException.badRequest("动态壁纸必须上传视频");
        }
        if (BACKGROUND_PAGES.contains(carousel.getPageKey()) && isBlank(carousel.getImageUrl())) {
            throw BusinessException.badRequest("背景封面图不能为空");
        }
        carouselMapper.updateById(carousel);
        disableSiblingBackgrounds(carousel);
        evictCarouselCache();
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        carouselMapper.deleteById(id);
        evictCarouselCache();
    }

    /**
     * 轮播图写操作统一失效（§5.3 陷阱 4）：carousel 表被 /api/carousel 与 /api/page-background
     * 共用，且 write 可能改动 pageKey/status/sortOrder，故同时失效首页轮播与全部背景页缓存。
     * <p>
     * <b>用 {@link CacheKeys#ALL_BG_KEYS} 而非手写若干 {@code bg("...")}</b>：此前这里写死了两个
     * 页面键，而白名单有第三个（{@code works}）—— 于是 {@code cache:bg:works} 被预热但永不被清除，
     * 管理员改「作品页」背景后前台最长 30 分钟不生效，且无任何强制刷新手段（B-03）。
     */
    private void evictCarouselCache() {
        List<String> keys = new ArrayList<>();
        keys.add(CacheKeys.CAROUSEL_HOME);
        keys.addAll(CacheKeys.ALL_BG_KEYS);
        cache.evictAfterCommit(keys.toArray(String[]::new));
    }

    private Carousel getOrThrow(Long id) {
        return Optional.ofNullable(carouselMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("轮播图不存在"));
    }

    /**
     * 单背景页面（about/work-detail/works）每页只应有一条生效背景。
     * 保存一条「启用」的背景后，自动停用同页其余启用项，避免出现"改了却不生效"的困惑。
     */
    private void disableSiblingBackgrounds(Carousel saved) {
        if (!BACKGROUND_PAGES.contains(saved.getPageKey())
                || saved.getStatus() == null || saved.getStatus() != 1) {
            return;
        }
        List<Carousel> siblings = carouselMapper
                .findByPageKeyAndStatusOrderBySortOrderAscIdAsc(saved.getPageKey(), 1);
        for (Carousel sibling : siblings) {
            if (!sibling.getId().equals(saved.getId())) {
                sibling.setStatus(0);
                carouselMapper.updateById(sibling);
            }
        }
    }

    private static String normalizePageKey(String pageKey) {
        if (isBlank(pageKey)) {
            return "home";
        }
        String value = pageKey.trim();
        if (!PAGE_KEYS.contains(value)) {
            throw BusinessException.badRequest("不支持的绑定页面：" + value);
        }
        return value;
    }

    private static String normalizeMediaType(String mediaType) {
        if (isBlank(mediaType)) {
            return "image";
        }
        String value = mediaType.trim().toLowerCase();
        if (!MEDIA_TYPES.contains(value)) {
            throw BusinessException.badRequest("不支持的资源类型：" + value);
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record CarouselForm(String imageUrl, String title, String subtitle,
                               String linkUrl, Integer sortOrder, Boolean enabled,
                               String pageKey, String mediaType, String videoUrl) {
    }
}
