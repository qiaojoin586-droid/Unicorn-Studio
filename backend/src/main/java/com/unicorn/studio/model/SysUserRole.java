package com.unicorn.studio.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员-角色关联表。（已迁移至 MyBatis Plus） */
@TableName("sys_user_role")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysUserRole {

    private Long userId;

    private Long roleId;
}
