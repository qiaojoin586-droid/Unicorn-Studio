package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.mapper.SysPermissionMapper;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.SysUserRole;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限加载：用户角色 + 权限码集合。
 * SUPER_ADMIN 角色加载全量权限码（代码级绕过）；其余角色实时 join 查询，修改即时生效。
 */
@Service
public class AdminPermissionService {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** 用户的角色与权限码集合 */
    public record LoadResult(List<SysRole> roles, Set<String> permissions) {
    }

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;

    public AdminPermissionService(SysUserRoleMapper userRoleMapper,
                                  SysRoleMapper roleMapper,
                                  SysPermissionMapper permissionMapper) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    public LoadResult loadForUser(SysUser user) {
        List<Long> roleIds = userRoleMapper.findByIdUserId(user.getId()).stream()
                .map(SysUserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return new LoadResult(List.of(), Set.of());
        }
        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        Set<String> permissions = new LinkedHashSet<>();

        boolean isSuperAdmin = roles.stream().anyMatch(r -> SUPER_ADMIN.equals(r.getCode()));
        if (isSuperAdmin) {
            permissionMapper.selectList(new LambdaQueryWrapper<>()).forEach(p -> permissions.add(p.getCode()));
        } else {
            permissions.addAll(permissionMapper.findCodesByRoleIds(roleIds));
        }
        return new LoadResult(roles, permissions);
    }
}
