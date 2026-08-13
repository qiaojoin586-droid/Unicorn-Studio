package com.unicorn.studio.repository;

import com.unicorn.studio.model.SysUserRole;
import com.unicorn.studio.model.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SysUserRoleRepository extends JpaRepository<SysUserRole, UserRoleId> {

    /** @EmbeddedId 属性需扁平化路径 id.userId */
    List<SysUserRole> findByIdUserId(Long userId);

    /** 角色被引用的管理员数 > 0 时禁止删除角色 */
    @Query("select ur from SysUserRole ur where ur.id.roleId = :roleId")
    List<SysUserRole> findByRoleId(@Param("roleId") Long roleId);

    void deleteByIdUserId(Long userId);
}
