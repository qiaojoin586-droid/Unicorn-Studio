package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 权限码（MENU 控制路由可见性，BUTTON 控制按钮级别）。 */
@Entity
@Table(name = "sys_permission")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SysPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 50)
    private String module;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private Integer sortOrder;
}
