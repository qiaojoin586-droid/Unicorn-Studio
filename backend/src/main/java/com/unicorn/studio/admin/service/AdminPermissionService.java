package com.unicorn.studio.admin.service;

import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.SysUserRole;
import com.unicorn.studio.repository.SysPermissionRepository;
import com.unicorn.studio.repository.SysRoleRepository;
import com.unicorn.studio.repository.SysUserRoleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    private final SysUserRoleRepository userRoleRepository;
    private final SysRoleRepository roleRepository;
    private final SysPermissionRepository permissionRepository;

    public AdminPermissionService(SysUserRoleRepository userRoleRepository,
                                  SysRoleRepository roleRepository,
                                  SysPermissionRepository permissionRepository) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
    }

    public LoadResult loadForUser(SysUser user) {
        List<Long> roleIds = userRoleRepository.findByIdUserId(user.getId()).stream()
                .map(SysUserRole::getId)
                .map(id -> id.getRoleId())
                .toList();
        if (roleIds.isEmpty()) {
            return new LoadResult(List.of(), Set.of());
        }
        List<SysRole> roles = roleRepository.findAllById(roleIds);
        Set<String> permissions = new LinkedHashSet<>();

        boolean isSuperAdmin = roles.stream().anyMatch(r -> SUPER_ADMIN.equals(r.getCode()));
        if (isSuperAdmin) {
            permissionRepository.findAll().forEach(p -> permissions.add(p.getCode()));
        } else {
            permissions.addAll(permissionRepository.findCodesByRoleIds(roleIds));
        }
        return new LoadResult(roles, permissions);
    }
}
