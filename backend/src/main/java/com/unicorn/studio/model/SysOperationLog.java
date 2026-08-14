package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 审计日志：后台写接口操作留痕（@AuditLog 切面写入）。（已迁移至 MyBatis Plus） */
@TableName("sys_operation_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysOperationLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    private String module;

    private String action;

    private String method;

    private String path;

    /** 已脱敏的参数 JSON */
    private String params;

    private String ip;

    private String status;

    private String errorMsg;

    private Integer durationMs;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
