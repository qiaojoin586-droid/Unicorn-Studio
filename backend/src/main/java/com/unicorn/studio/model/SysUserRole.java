package com.unicorn.studio.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员-角色关联表 POJO。
 * 复合主键 (user_id, role_id)，非 MyBatis-Plus 实体，由 {@code SysUserRoleMapper} 注解 SQL 操作。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysUserRole {

    private Long userId;

    private Long roleId;
}
