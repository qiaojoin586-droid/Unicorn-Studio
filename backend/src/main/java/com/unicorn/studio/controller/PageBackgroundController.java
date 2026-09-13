package com.unicorn.studio.controller;

import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Carousel;
import com.unicorn.studio.vo.PageBackgroundVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 前台页面背景（V11）：复用 carousel 表，按 page_key 取「排序最靠前的启用项」作为该页背景。
 * <p>
 * 首页 Hero 轮播仍走 /api/carousel；本接口只服务单背景页面。Redis 缓存 30 min（A6）。
 * 未知 pageKey 返回 enabled=false（而非报错），保证前台在任何情况下都能优雅降级。
 */
@RestController
@RequestMapping("/api/page-background")
public class PageBackgroundController {

    /**
     * 允许绑定单背景的页面白名单。
     * <p>
     * 取自 {@link CacheKeys#BG_PAGE_KEYS} 而非在这里另写一份：白名单与后台的缓存失效点
     * 必须来自同一份定义，否则「新增了页面但忘了加失效」会再次发生（B-03 就是这么来的）。
     */
    private static final Set<String> SUPPORTED_PAGES = Set.copyOf(CacheKeys.BG_PAGE_KEYS);

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final CarouselMapper carouselMapper;
    private final CacheHelper cache;

    public PageBackgroundController(CarouselMapper carouselMapper, CacheHelper cache) {
        this.carouselMapper = carouselMapper;
        this.cache = cache;
    }

    @GetMapping("/{pageKey}")
    public ApiResponse<PageBackgroundVO> get(@PathVariable String pageKey) {
        if (!SUPPORTED_PAGES.contains(pageKey)) {
            return ApiResponse.ok(PageBackgroundVO.none());
        }
        String cacheKey = CacheKeys.bg(pageKey);
        PageBackgroundVO vo = cache.get(cacheKey, PageBackgroundVO.class).orElseGet(() -> {
            List<Carousel> items = carouselMapper
                    .findByPageKeyAndStatusOrderBySortOrderAscIdAsc(pageKey, 1);
            PageBackgroundVO bg = items.isEmpty() ? PageBackgroundVO.none() : PageBackgroundVO.from(items.get(0));
            cache.set(cacheKey, bg, CACHE_TTL);
            return bg;
        });
        return ApiResponse.ok(vo);
    }
}
