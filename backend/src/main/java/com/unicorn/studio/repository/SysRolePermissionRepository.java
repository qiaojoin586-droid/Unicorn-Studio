package com.unicorn.studio.repository;

import com.unicorn.studio.model.RolePermissionId;
import com.unicorn.studio.model.SysRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysRolePermissionRepository extends JpaRepository<SysRolePermission, RolePermissionId> {

    /** @EmbeddedId 属性需扁平化路径 id.roleId */
    List<SysRolePermission> findByIdRoleId(Long roleId);

    void deleteByIdRoleId(Long roleId);
}
