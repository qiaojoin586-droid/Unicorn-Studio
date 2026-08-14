package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SysPermissionMapper;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysRolePermissionMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysPermission;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysRolePermission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 角色管理：CRUD + 权限勾选树（实施方案 §3.6）。
 * builtin=1 不可删改；有用户引用时禁止删除。
 */
@Service
public class RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysPermissionMapper permissionMapper;

    public RoleService(SysRoleMapper roleMapper,
                       SysRolePermissionMapper rolePermissionMapper,
                       SysUserRoleMapper userRoleMapper,
                       SysPermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
    }

    public record RoleItem(SysRole role, List<Long> permissionIds) {
    }

    public List<RoleItem> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .map(r -> new RoleItem(r, permissionIdsOf(r.getId())))
                .toList();
    }

    @Transactional
    public SysRole create(String code, String name, String description, List<Long> permissionIds) {
        if (roleMapper.findByCode(code).isPresent()) {
            throw BusinessException.conflict("角色编码已存在");
        }
        SysRole role = SysRole.builder()
                .code(code.trim().toUpperCase())
                .name(name)
                .description(description)
                .builtin(0)
                .status(1)
                .build();
        roleMapper.insert(role);
        replacePermissions(role.getId(), permissionIds);
        return role;
    }

    @Transactional
    public void update(Long id, String name, String description, Integer status, List<Long> permissionIds) {
        SysRole role = getOrThrow(id);
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw BusinessException.badRequest("内置角色不可修改");
        }
        if (name != null) {
            role.setName(name);
        }
        if (description != null) {
            role.setDescription(description);
        }
        if (status != null) {
            role.setStatus(status);
        }
        if (permissionIds != null) {
            replacePermissions(id, permissionIds);
        }
        roleMapper.updateById(role);
    }

    @Transactional
    public void delete(Long id) {
        SysRole role = getOrThrow(id);
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw BusinessException.badRequest("内置角色不可删除");
        }
        if (!userRoleMapper.findByRoleId(id).isEmpty()) {
            throw BusinessException.conflict("仍有管理员引用该角色，无法删除");
        }
        rolePermissionMapper.deleteByIdRoleId(id);
        roleMapper.deleteById(role.getId());
    }

    /** 权限按模块分组（前端权限勾选树数据源）。 */
    public Map<String, List<Map<String, Object>>> permissionTree() {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        List<SysPermission> permissions = permissionMapper.findAllByOrderByModuleAscSortOrderAsc();
        for (SysPermission p : permissions) {
            groups.computeIfAbsent(p.getModule() == null ? "其他" : p.getModule(), k -> new java.util.ArrayList<>())
                    .add(Map.of("id", p.getId(), "code", p.getCode(), "name", p.getName(), "type", p.getType()));
        }
        return groups;
    }

    private SysRole getOrThrow(Long id) {
        return Optional.ofNullable(roleMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("角色不存在"));
    }

    private List<Long> permissionIdsOf(Long roleId) {
        return rolePermissionMapper.findByIdRoleId(roleId).stream()
                .map(SysRolePermission::getPermissionId).toList();
    }

    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.deleteByIdRoleId(roleId);
        if (permissionIds != null) {
            for (Long permissionId : permissionIds) {
                rolePermissionMapper.insert(new SysRolePermission(roleId, permissionId));
            }
        }
    }
}
