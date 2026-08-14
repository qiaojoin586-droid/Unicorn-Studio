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

/** 访问日志（PV 埋点，DNT 忽略；ip 存 SHA-256 哈希）。（已迁移至 MyBatis Plus） */
@TableName("visit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String pagePath;

    private String ipHash;

    private String ua;

    private String referer;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime visitedAt;
}
