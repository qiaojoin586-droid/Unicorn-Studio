package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SysPermission;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    default List<SysPermission> findAllByOrderByModuleAscSortOrderAsc() {
        return selectList(new LambdaQueryWrapper<SysPermission>()
                .orderByAsc(SysPermission::getModule, SysPermission::getSortOrder));
    }

    default List<SysPermission> findByCodeIn(List<String> codes) {
        return selectList(new LambdaQueryWrapper<SysPermission>().in(SysPermission::getCode, codes));
    }

    /** 按角色 id 集合查权限码（实时权限，角色修改即时生效） */
    default List<String> findCodesByRoleIds(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return selectObjs(new QueryWrapper<SysPermission>()
                        .select("code")
                        .inSql("id", "select permission_id from sys_role_permission where role_id in ("
                                + roleIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")"))
                .stream().map(o -> String.valueOf(o)).toList();
    }
}
