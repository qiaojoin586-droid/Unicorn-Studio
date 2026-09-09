package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 串行化超管初始化，避免多实例同时创建两个 SUPER_ADMIN 账号。 */
    @Select("SELECT id, code, name, description, builtin, status, created_at AS createdAt " +
            "FROM sys_role WHERE code = #{code} FOR UPDATE")
    SysRole findByCodeForUpdate(@Param("code") String code);

    default Optional<SysRole> findByCode(String code) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code)));
    }
}
