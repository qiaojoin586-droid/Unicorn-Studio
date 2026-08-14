package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.Carousel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 轮播图管理（实施方案 §3.3）。 */
@Service
public class CarouselAdminService {

    private final CarouselMapper carouselMapper;

    public CarouselAdminService(CarouselMapper carouselMapper) {
        this.carouselMapper = carouselMapper;
    }

    public PageResult<Carousel> list(int page, int size) {
        Page<Carousel> result = carouselMapper.selectPage(
                new Page<>(page + 1, size),
                new LambdaQueryWrapper<Carousel>().orderByAsc(Carousel::getSortOrder, Carousel::getId));
        return PageResult.of(result);
    }

    @Transactional
    public Carousel create(CarouselForm form) {
        Carousel carousel = Carousel.builder()
                .imageUrl(form.imageUrl())
                .title(form.title())
                .subtitle(form.subtitle())
                .linkUrl(form.linkUrl())
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(Boolean.TRUE.equals(form.enabled()) ? 1 : 0)
                .build();
        carouselMapper.insert(carousel);
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
        carouselMapper.updateById(carousel);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        carouselMapper.deleteById(id);
    }

    private Carousel getOrThrow(Long id) {
        return Optional.ofNullable(carouselMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("轮播图不存在"));
    }

    public record CarouselForm(String imageUrl, String title, String subtitle,
                               String linkUrl, Integer sortOrder, Boolean enabled) {
    }
}
