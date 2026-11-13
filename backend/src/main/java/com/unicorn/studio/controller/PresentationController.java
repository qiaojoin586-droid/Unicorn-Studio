package com.unicorn.studio.controller;

import com.unicorn.studio.admin.service.PresentationService;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.vo.PresentationVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/site/presentation")
public class PresentationController {
    private final PresentationService presentation;
    public PresentationController(PresentationService presentation) { this.presentation = presentation; }
    @GetMapping public ApiResponse<PresentationVO> get() { return ApiResponse.ok(presentation.load()); }
}