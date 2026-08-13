package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 新闻/公告实体。公开接口仅返回 status=published。 */
@Entity
@Table(name = "news")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    private String category;

    private String coverUrl;

    @Column(length = 500)
    private String summary;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    /** sys_user.id */
    private Long authorId;

    /** 0/1 */
    @Column(nullable = false)
    private Integer featured;

    @Column(nullable = false)
    private Integer sortOrder;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Integer viewCount;

    private LocalDateTime publishedAt;

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
            status = "draft";
        }
        if (featured == null) {
            featured = 0;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (viewCount == null) {
            viewCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
