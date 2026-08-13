package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 角色-权限关联表。 */
@Entity
@Table(name = "sys_role_permission")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysRolePermission {

    @EmbeddedId
    private RolePermissionId id;
}
