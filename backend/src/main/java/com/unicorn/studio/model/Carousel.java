package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 首页轮播图。 */
@Entity
@Table(name = "carousel")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Carousel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String imageUrl;

    @Column(length = 100)
    private String title;

    @Column(length = 200)
    private String subtitle;

    private String linkUrl;

    @Column(nullable = false)
    private Integer sortOrder;

    /** 1启用 0停用 */
    @Column(nullable = false)
    private Integer status;

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
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (status == null) {
            status = 1;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
