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

/**
 * 能力培养条目（首页"我们培养的能力"区块）。
 *
 * 字段设计说明（为后续升级预留）：
 * - status：published 展示 / offline 下线；管理后台上线后可扩展 draft 草稿流，无需改表
 * - sort_order：后台可拖拽排序，无需改表
 * - created_at / updated_at：审计与内容更新追踪
 * - icon：当前为 emoji，后续可无缝切换为图标库标识或图片 URL（icon 与 cover_url 可并存）
 * （已迁移至 MyBatis Plus）
 */
@TableName("service_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceItem {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    /** emoji 或图标标识；为空时前端显示默认图标 */
    private String icon;

    /** 排序值，越小越靠前 */
    private int sortOrder;

    /** published / offline（预留 draft） */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
