package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 访问日志（PV 埋点，DNT 忽略；ip 存 SHA-256 哈希）。 */
@Entity
@Table(name = "visit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String pagePath;

    @Column(nullable = false, length = 64)
    private String ipHash;

    @Column(length = 255)
    private String ua;

    @Column(length = 500)
    private String referer;

    @Column(nullable = false)
    private LocalDateTime visitedAt;

    @PrePersist
    void onCreate() {
        if (visitedAt == null) {
            visitedAt = LocalDateTime.now();
        }
    }
}
