package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 后台角色。builtin=1 的内置角色不可删改。 */
@Entity
@Table(name = "sys_role")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 255)
    private String description;

    /** 1=内置不可删改 */
    @Column(nullable = false)
    private Integer builtin;

    @Column(nullable = false)
    private Integer status;

    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = 1;
        }
        if (builtin == null) {
            builtin = 0;
        }
    }
}
