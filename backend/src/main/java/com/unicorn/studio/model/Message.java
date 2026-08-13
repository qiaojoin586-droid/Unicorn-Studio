package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 前台留言/联系表单。 */
@Entity
@Table(name = "message")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String contact;

    @Column(nullable = false, length = 2000)
    private String content;

    /** contact_form/guestbook */
    @Column(nullable = false, length = 20)
    private String source;

    /** new/replied/closed */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String replyContent;

    private Long repliedBy;

    private LocalDateTime repliedAt;

    @Column(length = 64)
    private String ipHash;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "new";
        }
    }
}
