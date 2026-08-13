package com.unicorn.studio.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员-角色关联表。 */
@Entity
@Table(name = "sys_user_role")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SysUserRole {

    @EmbeddedId
    private UserRoleId id;
}
