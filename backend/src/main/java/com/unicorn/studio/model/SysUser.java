package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 后台管理员账号。密码 BCrypt；锁定与刷新令牌持久化（重启/多实例不丢）。
 */
@Entity
@Table(name = "sys_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(length = 50)
    private String nickname;

    private String avatar;

    /** 1启用 0禁用 */
    @Column(nullable = false)
    private Integer status;

    /** 首次登录强制改密（AdminBootstrap 初始账号置 1） */
    @Column(nullable = false)
    private Integer mustChangePassword;

    @Column(length = 500)
    private String refreshToken;

    private LocalDateTime refreshTokenExpiresAt;

    @Column(nullable = false)
    private Integer failedLoginAttempts;

    /** NULL=未锁定 */
    private LocalDateTime lockedUntil;

    private LocalDateTime lastLoginAt;

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
            status = 1;
        }
        if (mustChangePassword == null) {
            mustChangePassword = 0;
        }
        if (failedLoginAttempts == null) {
            failedLoginAttempts = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
