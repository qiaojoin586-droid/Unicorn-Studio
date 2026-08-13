package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 审计日志：后台写接口操作留痕（@AuditLog 切面写入）。 */
@Entity
@Table(name = "sys_operation_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysOperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Column(length = 50)
    private String username;

    @Column(length = 50)
    private String module;

    @Column(length = 50)
    private String action;

    @Column(length = 10)
    private String method;

    @Column(length = 255)
    private String path;

    /** 已脱敏的参数 JSON */
    @Column(columnDefinition = "TEXT")
    private String params;

    @Column(length = 45)
    private String ip;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String errorMsg;

    private Integer durationMs;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
