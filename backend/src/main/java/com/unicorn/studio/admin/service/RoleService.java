package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.model.RolePermissionId;
import com.unicorn.studio.model.SysPermission;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysRolePermission;
import com.unicorn.studio.repository.SysPermissionRepository;
import com.unicorn.studio.repository.SysRolePermissionRepository;
import com.unicorn.studio.repository.SysRoleRepository;
import com.unicorn.studio.repository.SysUserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理：CRUD + 权限勾选树（实施方案 §3.6）。
 * builtin=1 不可删改；有用户引用时禁止删除。
 */
@Service
public class RoleService {

    private final SysRoleRepository roleRepository;
    private final SysRolePermissionRepository rolePermissionRepository;
    private final SysUserRoleRepository userRoleRepository;
    private final SysPermissionRepository permissionRepository;

    public RoleService(SysRoleRepository roleRepository,
                       SysRolePermissionRepository rolePermissionRepository,
                       SysUserRoleRepository userRoleRepository,
                       SysPermissionRepository permissionRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRoleRepository = userRoleRepository;
        this.permissionRepository = permissionRepository;
    }

    public record RoleItem(SysRole role, List<Long> permissionIds) {
    }

    public List<RoleItem> listAll() {
        return roleRepository.findAll().stream()
                .map(r -> new RoleItem(r, permissionIdsOf(r.getId())))
                .toList();
    }

    @Transactional
    public SysRole create(String code, String name, String description, List<Long> permissionIds) {
        if (roleRepository.findByCode(code).isPresent()) {
            throw BusinessException.conflict("角色编码已存在");
        }
        SysRole role = SysRole.builder()
                .code(code.trim().toUpperCase())
                .name(name)
                .description(description)
                .builtin(0)
                .status(1)
                .build();
        role = roleRepository.save(role);
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
        roleRepository.save(role);
    }

    @Transactional
    public void delete(Long id) {
        SysRole role = getOrThrow(id);
        if (role.getBuiltin() != null && role.getBuiltin() == 1) {
            throw BusinessException.badRequest("内置角色不可删除");
        }
        if (!userRoleRepository.findByRoleId(id).isEmpty()) {
            throw BusinessException.conflict("仍有管理员引用该角色，无法删除");
        }
        rolePermissionRepository.deleteByIdRoleId(id);
        roleRepository.delete(role);
    }

    /** 权限按模块分组（前端权限勾选树数据源）。 */
    public Map<String, List<Map<String, Object>>> permissionTree() {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        List<SysPermission> permissions = permissionRepository.findAllByOrderByModuleAscSortOrderAsc();
        for (SysPermission p : permissions) {
            groups.computeIfAbsent(p.getModule() == null ? "其他" : p.getModule(), k -> new java.util.ArrayList<>())
                    .add(Map.of("id", p.getId(), "code", p.getCode(), "name", p.getName(), "type", p.getType()));
        }
        return groups;
    }

    private SysRole getOrThrow(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("角色不存在"));
    }

    private List<Long> permissionIdsOf(Long roleId) {
        return rolePermissionRepository.findByIdRoleId(roleId).stream()
                .map(rp -> rp.getId().getPermissionId()).toList();
    }

    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionRepository.deleteByIdRoleId(roleId);
        if (permissionIds != null) {
            for (Long permissionId : permissionIds) {
                rolePermissionRepository.save(new SysRolePermission(new RolePermissionId(roleId, permissionId)));
            }
        }
    }
}
