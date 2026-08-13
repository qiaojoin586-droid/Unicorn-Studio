package com.unicorn.studio.repository;

import com.unicorn.studio.model.SysPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SysPermissionRepository extends JpaRepository<SysPermission, Long> {

    List<SysPermission> findAllByOrderByModuleAscSortOrderAsc();

    List<SysPermission> findByCodeIn(List<String> codes);

    /** 按角色 id 集合查权限码（实时权限，角色修改即时生效） */
    @Query("select p.code from SysPermission p where p.id in " +
            "(select rp.id.permissionId from SysRolePermission rp where rp.id.roleId in :roleIds)")
    List<String> findCodesByRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
