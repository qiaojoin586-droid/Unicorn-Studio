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
 * 我们培养的能力条目（首页"我们培养的能力"区块）。
 *
 * 字段设计与 service_item 保持一致（为后续升级预留）：
 * - status：published 展示 / offline 下线
 * - sort_order：后台可排序，无需改表
 * - created_at / updated_at：审计与内容更新追踪
 * - icon：当前为 emoji，后续可无缝切换为图标库标识或图片 URL
 */
@TableName("capability_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Capability {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    /** emoji 或图标标识；为空时前端显示默认图标 */
    private String icon;

    /** 排序值，越小越靠前 */
    private int sortOrder;

    /** published / offline */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
