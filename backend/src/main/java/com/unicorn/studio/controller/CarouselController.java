package com.unicorn.studio.controller;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.CarouselVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 前台轮播图（仅启用的，按 sort_order，实施方案 §3.7）；Redis 缓存 30 min（A7）。 */
@RestController
@RequestMapping("/api/carousel")
public class CarouselController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final JavaType LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, CarouselVO.class);

    private final CarouselMapper carouselMapper;
    private final CacheHelper cache;

    public CarouselController(CarouselMapper carouselMapper, CacheHelper cache) {
        this.carouselMapper = carouselMapper;
        this.cache = cache;
    }

    @GetMapping
    public ApiResponse<Map<String, List<CarouselVO>>> list() {
        // V11：轮播图新增 page_key 维度后，首页只取 home 分组。
        // 既有数据默认 page_key='home'，故对原行为等价。
        List<CarouselVO> items = cache.<List<CarouselVO>>get(CacheKeys.CAROUSEL_HOME, LIST_TYPE).orElseGet(() -> {
            List<CarouselVO> list = carouselMapper
                    .findByPageKeyAndStatusOrderBySortOrderAscIdAsc("home", 1).stream()
                    .map(CarouselVO::from)
                    .toList();
            cache.set(CacheKeys.CAROUSEL_HOME, list, CACHE_TTL);
            return list;
        });
        return ApiResponse.ok(Map.of("list", items));
    }
}
