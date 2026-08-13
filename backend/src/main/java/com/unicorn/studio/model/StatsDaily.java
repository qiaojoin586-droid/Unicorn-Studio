package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 每日聚合统计（由定时任务按 visit_log 等汇总，stat_date 唯一）。 */
@Entity
@Table(name = "stats_daily")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate statDate;

    @Column(nullable = false)
    private Integer pv;

    @Column(nullable = false)
    private Integer uv;

    @Column(nullable = false)
    private Integer newUsers;

    @Column(nullable = false)
    private Integer newMessages;

    @Column(nullable = false)
    private Integer newComments;

    @PrePersist
    void onCreate() {
        if (pv == null) pv = 0;
        if (uv == null) uv = 0;
        if (newUsers == null) newUsers = 0;
        if (newMessages == null) newMessages = 0;
        if (newComments == null) newComments = 0;
    }
}
