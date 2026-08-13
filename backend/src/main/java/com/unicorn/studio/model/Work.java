package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 作品实体：详情页内容与卡片展示字段合一，公开接口仅返回 status=published。
 */
@Entity
@Table(name = "work")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Work {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    private String category;

    private String coverUrl;

    @Column(length = 1000)
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String content;

    private String externalLink;

    private boolean featured;

    private int sortOrder;

    @Column(nullable = false)
    private String status;

    private int viewCount;

    private LocalDateTime publishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---- 卡片展示字段：暂无真实封面图，以图标+渐变占位；有 coverUrl 后可弱化 ----
    private String icon;

    private String coverClass;

    /** award / paper */
    private String tagType;

    private String authors;

    private String dateLabel;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
