package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysRolePermission;

import java.util.List;

public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    default List<SysRolePermission> findByIdRoleId(Long roleId) {
        return selectList(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
    }

    default void deleteByIdRoleId(Long roleId) {
        delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
    }
}
