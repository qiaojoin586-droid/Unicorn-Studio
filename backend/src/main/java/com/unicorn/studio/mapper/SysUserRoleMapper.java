package com.unicorn.studio.mapper;

import com.unicorn.studio.model.SysUserRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 管理员-角色关联表 mapper。
 * sys_user_role 为 (user_id, role_id) 复合主键，MyBatis-Plus 不支持复合主键实体，
 * 故不继承 BaseMapper，改用注解 SQL（仅按列的增删查，无按主键 CRUD 需求）。
 */
public interface SysUserRoleMapper {

    @Select("SELECT user_id AS userId, role_id AS roleId FROM sys_user_role WHERE user_id = #{userId}")
    List<SysUserRole> findByIdUserId(Long userId);

    /** 批量按用户查关联（管理员列表避免 N+1） */
    @Select({"<script>",
            "SELECT user_id AS userId, role_id AS roleId FROM sys_user_role WHERE user_id IN",
            "<foreach collection='userIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<SysUserRole> findByUserIds(@org.apache.ibatis.annotations.Param("userIds") List<Long> userIds);

    /** 角色被引用的管理员数 > 0 时禁止删除角色 */
    @Select("SELECT user_id AS userId, role_id AS roleId FROM sys_user_role WHERE role_id = #{roleId}")
    List<SysUserRole> findByRoleId(Long roleId);

    @Insert("INSERT INTO sys_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int insert(SysUserRole row);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteByIdUserId(Long userId);
}
