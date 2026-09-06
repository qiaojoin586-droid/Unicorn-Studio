package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.CarouselAdminService;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.audit.AuditLog;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.Carousel;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 轮播图管理（实施方案 §3.3）。 */
@RestController
@RequestMapping("/api/admin/carousel")
public class CarouselAdminController {

    private final CarouselAdminService carouselService;

    public CarouselAdminController(CarouselAdminService carouselService) {
        this.carouselService = carouselService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('carousel:list')")
    public ApiResponse<PageResult<Carousel>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String group) {
        return ApiResponse.ok(carouselService.list(page - 1, size, group));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('carousel:create')")
    @AuditLog(module = "轮播图", action = "新增轮播图")
    public ApiResponse<Map<String, Object>> create(@RequestBody CarouselAdminService.CarouselForm form) {
        Carousel carousel = carouselService.create(form);
        return ApiResponse.ok(Map.of("id", carousel.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('carousel:edit')")
    @AuditLog(module = "轮播图", action = "编辑轮播图")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody CarouselAdminService.CarouselForm form) {
        carouselService.update(id, form);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('carousel:delete')")
    @AuditLog(module = "轮播图", action = "删除轮播图")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        carouselService.delete(id);
        return ApiResponse.ok();
    }
}
