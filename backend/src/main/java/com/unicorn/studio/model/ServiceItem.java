package com.unicorn.studio.model;

import jakarta.persistence.*;
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
 */
@Entity
@Table(name = "service_item")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    /** emoji 或图标标识；为空时前端显示默认图标 */
    @Column(length = 50)
    private String icon;

    /** 排序值，越小越靠前 */
    @Column(nullable = false)
    private int sortOrder;

    /** published / offline（预留 draft） */
    @Column(nullable = false, length = 20)
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = "published";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
