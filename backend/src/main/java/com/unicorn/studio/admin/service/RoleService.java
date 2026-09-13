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
import java.util.Set;
import java.util.stream.Collectors;

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
    private final AdminPermissionService permissionService;

    public RoleService(SysRoleMapper roleMapper,
                       SysRolePermissionMapper rolePermissionMapper,
                       SysUserRoleMapper userRoleMapper,
                       SysPermissionMapper permissionMapper,
                       AdminPermissionService permissionService) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.permissionService = permissionService;
    }

    public record RoleItem(SysRole role, List<Long> permissionIds) {
    }

    public List<RoleItem> listAll() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<>());
        // 一次性加载全部角色的权限关联，避免逐角色查询的 N+1
        Map<Long, List<Long>> permissionIdsByRole = new LinkedHashMap<>();
        if (!roles.isEmpty()) {
            rolePermissionMapper.findByRoleIds(roles.stream().map(SysRole::getId).toList())
                    .forEach(rp -> permissionIdsByRole
                            .computeIfAbsent(rp.getRoleId(), k -> new java.util.ArrayList<>())
                            .add(rp.getPermissionId()));
        }
        return roles.stream()
                .map(r -> new RoleItem(r, permissionIdsByRole.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    @Transactional
    public SysRole create(String code, String name, String description, List<Long> permissionIds) {
        // 先归一化再做唯一性检查（与入库值一致，防止大小写/空白绕过检查后撞唯一索引）
        String normalizedCode = code.trim().toUpperCase();
        if (AdminPermissionService.SUPER_ADMIN.equals(normalizedCode)) {
            throw BusinessException.badRequest("SUPER_ADMIN 是系统保留角色编码");
        }
        if (roleMapper.findByCode(normalizedCode).isPresent()) {
            throw BusinessException.conflict("角色编码已存在");
        }
        SysRole role = SysRole.builder()
                .code(normalizedCode)
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
        // 角色权限/名称变更 → 该角色下所有用户的权限缓存失效（§6.3 失效表，安全边界 R4）
        permissionService.evictPermissionCacheForRole(id);
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
        // 删除被引用即拦截，此处失效为兜底（无用户则空操作）
        permissionService.evictPermissionCacheForRole(id);
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

    /**
     * 重建角色的权限关联（B-11）。
     * <p>
     * 原实现是循环内「逐条 {@code selectById} 校验 + 逐条 {@code insert}」——
     * 勾 40 个权限就是 <b>40 次查询 + 40 次插入 = 80 次往返</b>，整段还在事务里，
     * 事务持有时长与勾选数量线性相关。
     * <p>
     * 改为「一次 {@code selectBatchIds} 校验 + 一条批量 INSERT」= 2 次往返。
     * 校验语义不变（仍是「引用不存在则 400，防悬挂关联」），且因为先把全部 id 查出来，
     * 报错时能一次性给出所有不存在的 id，比逐个报更有用。
     */
    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.deleteByIdRoleId(roleId);
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        List<Long> distinctIds = permissionIds.stream().distinct().toList();
        Set<Long> existing = permissionMapper.selectBatchIds(distinctIds).stream()
                .map(SysPermission::getId)
                .collect(Collectors.toSet());
        List<Long> missing = distinctIds.stream().filter(id -> !existing.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw BusinessException.badRequest("权限不存在: " + missing);
        }
        rolePermissionMapper.insertBatch(distinctIds.stream()
                .map(permissionId -> new SysRolePermission(roleId, permissionId))
                .toList());
    }
}
