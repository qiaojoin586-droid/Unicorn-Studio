package com.unicorn.studio.controller;

import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Carousel;
import com.unicorn.studio.vo.PageBackgroundVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 前台页面背景（V11）：复用 carousel 表，按 page_key 取「排序最靠前的启用项」作为该页背景。
 * <p>
 * 首页 Hero 轮播仍走 /api/carousel；本接口只服务单背景页面。
 * 未知 pageKey 返回 enabled=false（而非报错），保证前台在任何情况下都能优雅降级。
 */
@RestController
@RequestMapping("/api/page-background")
public class PageBackgroundController {

    /** 允许绑定单背景的页面白名单 */
    private static final Set<String> SUPPORTED_PAGES = Set.of("about", "work-detail");

    private final CarouselMapper carouselMapper;

    public PageBackgroundController(CarouselMapper carouselMapper) {
        this.carouselMapper = carouselMapper;
    }

    @GetMapping("/{pageKey}")
    public ApiResponse<PageBackgroundVO> get(@PathVariable String pageKey) {
        if (!SUPPORTED_PAGES.contains(pageKey)) {
            return ApiResponse.ok(PageBackgroundVO.none());
        }
        List<Carousel> items = carouselMapper.findByPageKeyAndStatusOrderBySortOrderAscIdAsc(pageKey, 1);
        return ApiResponse.ok(items.isEmpty() ? PageBackgroundVO.none() : PageBackgroundVO.from(items.get(0)));
    }
}
