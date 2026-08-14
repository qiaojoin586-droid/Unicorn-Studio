package com.unicorn.studio.controller;

import com.unicorn.studio.mapper.CarouselMapper;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Carousel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 前台轮播图（仅启用的，按 sort_order，实施方案 §3.7）。 */
@RestController
@RequestMapping("/api/carousel")
public class CarouselController {

    private final CarouselMapper carouselMapper;

    public CarouselController(CarouselMapper carouselMapper) {
        this.carouselMapper = carouselMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, List<Carousel>>> list() {
        return ApiResponse.ok(Map.of("list", carouselMapper.findByStatusOrderBySortOrderAscIdAsc(1)));
    }
}
