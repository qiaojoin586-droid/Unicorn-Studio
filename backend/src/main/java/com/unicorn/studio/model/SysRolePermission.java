package com.unicorn.studio.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色-权限关联表 POJO。
 * 复合主键 (role_id, permission_id)，非 MyBatis-Plus 实体，由 {@code SysRolePermissionMapper} 注解 SQL 操作。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysRolePermission {

    private Long roleId;

    private Long permissionId;
}
