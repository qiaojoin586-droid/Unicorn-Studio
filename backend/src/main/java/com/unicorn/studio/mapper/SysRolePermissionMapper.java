package com.unicorn.studio.mapper;

import com.unicorn.studio.model.SysRolePermission;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-权限关联表 mapper。
 * sys_role_permission 为 (role_id, permission_id) 复合主键，MyBatis-Plus 不支持复合主键实体，
 * 故不继承 BaseMapper，改用注解 SQL（仅按列的增删查，无按主键 CRUD 需求）。
 */
public interface SysRolePermissionMapper {

    @Select("SELECT role_id AS roleId, permission_id AS permissionId FROM sys_role_permission WHERE role_id = #{roleId}")
    List<SysRolePermission> findByIdRoleId(Long roleId);

    /** 批量按角色查关联（角色列表避免 N+1） */
    @Select({"<script>",
            "SELECT role_id AS roleId, permission_id AS permissionId FROM sys_role_permission WHERE role_id IN",
            "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<SysRolePermission> findByRoleIds(@org.apache.ibatis.annotations.Param("roleIds") List<Long> roleIds);

    @Insert("INSERT INTO sys_role_permission(role_id, permission_id) VALUES(#{roleId}, #{permissionId})")
    int insert(SysRolePermission row);

    /**
     * 批量插入关联（B-11）：一条语句写完 N 条关联。
     * <p>
     * 原先 {@code replacePermissions} 是循环内逐条 {@code insert} —— 勾 40 个权限就是
     * 40 次往返，且整段在事务里，事务持有时长与勾选数量线性相关。
     * <p>
     * ⚠️ 调用方须保证非空（空列表会生成语法非法的 SQL）。
     */
    @Insert({"<script>",
            "INSERT INTO sys_role_permission(role_id, permission_id) VALUES",
            "<foreach collection='rows' item='r' separator=','>(#{r.roleId}, #{r.permissionId})</foreach>",
            "</script>"})
    int insertBatch(@org.apache.ibatis.annotations.Param("rows") List<SysRolePermission> rows);

    @Delete("DELETE FROM sys_role_permission WHERE role_id = #{roleId}")
    int deleteByIdRoleId(Long roleId);
}
