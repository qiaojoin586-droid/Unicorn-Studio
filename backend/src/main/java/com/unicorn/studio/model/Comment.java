package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评论（多态关联 target_type+target_id → work/news）。
 * 无外键约束，删除目标内容时由服务层同步清理（实施方案 §3.4）。
 */
@Entity
@Table(name = "comment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String targetType;

    @Column(nullable = false)
    private Long targetId;

    /** public_user.id；NULL=游客 */
    private Long userId;

    @Column(length = 50)
    private String guestNickname;

    /** 回复的评论ID（楼中楼） */
    private Long parentId;

    @Column(nullable = false, length = 1000)
    private String content;

    /** pending/approved/rejected */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 64)
    private String ipHash;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "pending";
        }
    }
}
