package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 前台注册用户。与后台 sys_user 体系隔离。 */
@Entity
@Table(name = "public_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(length = 100, unique = true)
    private String email;

    @Column(nullable = false, length = 100)
    private String password;

    private String avatar;

    /** 1正常 0禁言 2注销 */
    @Column(nullable = false)
    private Integer status;

    @Column(length = 255)
    private String banReason;

    /** NULL=永久禁言 */
    private LocalDateTime banUntil;

    @Column(length = 45)
    private String registerIp;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = 1;
        }
    }
}
