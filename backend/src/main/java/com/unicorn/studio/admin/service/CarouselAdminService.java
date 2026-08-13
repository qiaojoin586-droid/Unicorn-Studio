package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.model.Carousel;
import com.unicorn.studio.repository.CarouselRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 轮播图管理（实施方案 §3.3）。 */
@Service
public class CarouselAdminService {

    private final CarouselRepository carouselRepository;

    public CarouselAdminService(CarouselRepository carouselRepository) {
        this.carouselRepository = carouselRepository;
    }

    public PageResult<Carousel> list(int page, int size) {
        Page<Carousel> result = carouselRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "sortOrder", "id")));
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
        return carouselRepository.save(carousel);
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
        carouselRepository.save(carousel);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        carouselRepository.deleteById(id);
    }

    private Carousel getOrThrow(Long id) {
        return carouselRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("轮播图不存在"));
    }

    public record CarouselForm(String imageUrl, String title, String subtitle,
                               String linkUrl, Integer sortOrder, Boolean enabled) {
    }
}
