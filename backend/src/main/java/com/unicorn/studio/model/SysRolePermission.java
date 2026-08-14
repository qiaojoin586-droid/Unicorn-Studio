package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 角色-权限关联表。（已迁移至 MyBatis Plus） */
@TableName("sys_role_permission")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysRolePermission {

    private Long roleId;

    private Long permissionId;
}
