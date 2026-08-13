package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 站点设置 KV。group：basic/contact/social/seo/notify/content。 */
@Entity
@Table(name = "site_settings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String groupName;

    @Column(nullable = false, unique = true, length = 100)
    private String settingKey;

    @Column(columnDefinition = "TEXT")
    private String settingValue;

    @Column(length = 255)
    private String description;

    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
