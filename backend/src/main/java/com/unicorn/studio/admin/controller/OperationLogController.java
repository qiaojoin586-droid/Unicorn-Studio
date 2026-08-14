package com.unicorn.studio.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.SysOperationLogMapper;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.SysOperationLog;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 审计日志查询（实施方案 §3.6）。 */
@RestController
@RequestMapping("/api/admin/logs")
public class OperationLogController {

    private final SysOperationLogMapper logMapper;

    public OperationLogController(SysOperationLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('log:list')")
    public ApiResponse<PageResult<SysOperationLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.eq(SysOperationLog::getUsername, username);
        }
        if (module != null && !module.isBlank()) {
            wrapper.eq(SysOperationLog::getModule, module);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(SysOperationLog::getStatus, status);
        }
        if (from != null && !from.isBlank()) {
            wrapper.ge(SysOperationLog::getCreatedAt, LocalDate.parse(from).atStartOfDay());
        }
        if (to != null && !to.isBlank()) {
            wrapper.lt(SysOperationLog::getCreatedAt, LocalDate.parse(to).plusDays(1).atStartOfDay());
        }
        wrapper.orderByDesc(SysOperationLog::getId);
        // @RequestParam page 为 1-based（defaultValue = "1"），MP Page 亦为 1-based
        Page<SysOperationLog> logs = logMapper.selectPage(new Page<>(page, size), wrapper);
        return ApiResponse.ok(PageResult.of(logs));
    }
}
