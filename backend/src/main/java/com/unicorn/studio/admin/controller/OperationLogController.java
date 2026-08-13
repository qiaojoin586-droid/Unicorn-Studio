package com.unicorn.studio.admin.controller;

import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.model.ApiResponse;
import com.unicorn.studio.model.SysOperationLog;
import com.unicorn.studio.repository.SysOperationLogRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 审计日志查询（实施方案 §3.6）。 */
@RestController
@RequestMapping("/api/admin/logs")
public class OperationLogController {

    private final SysOperationLogRepository logRepository;

    public OperationLogController(SysOperationLogRepository logRepository) {
        this.logRepository = logRepository;
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
        Specification<SysOperationLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (username != null && !username.isBlank()) {
                predicates.add(cb.equal(root.get("username"), username));
            }
            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null && !from.isBlank()) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        LocalDate.parse(from).atStartOfDay()));
            }
            if (to != null && !to.isBlank()) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        LocalDate.parse(to).plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<SysOperationLog> logs = logRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return ApiResponse.ok(PageResult.of(logs));
    }
}
