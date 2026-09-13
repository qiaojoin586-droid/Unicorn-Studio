package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.RefreshTokenStore;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 管理员 CRUD（实施方案 §3.6）：
 * - 不能删自己；不能禁用/改自己角色；唯一 SUPER_ADMIN 不可删、禁用或改角色；
 * - 普通管理员可自由组合普通角色，但不能获得 SUPER_ADMIN；
 * - 列表带角色名；编辑角色时重建关联。
 */
@Service
public class AdminAccountService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminPermissionService permissionService;
    private final RefreshTokenStore refreshTokenStore;

    public AdminAccountService(SysUserMapper userMapper,
                               SysRoleMapper roleMapper,
                               SysUserRoleMapper userRoleMapper,
                               PasswordEncoder passwordEncoder,
                               AdminPermissionService permissionService,
                               RefreshTokenStore refreshTokenStore) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
        this.refreshTokenStore = refreshTokenStore;
    }

    /** 管理员列表项：user 为脱敏字段（不含 password/refreshToken） */
    public record AdminItem(Map<String, Object> user, List<String> roleCodes, List<String> roleNames) {
    }

    public PageResult<AdminItem> list(String keyword, int page, int size) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(SysUser::getUsername, like).or().like(SysUser::getNickname, like));
        }
        wrapper.orderByDesc(SysUser::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        Page<SysUser> result = userMapper.selectPage(PageResult.safePage(page + 1, size), wrapper);
        // 批量加载整页用户的角色，避免逐行 2~4 次查询的 N+1
        Map<Long, List<SysRole>> rolesByUser = batchRolesOf(
                result.getRecords().stream().map(SysUser::getId).toList());
        return PageResult.of(result, u -> {
            List<SysRole> roles = rolesByUser.getOrDefault(u.getId(), List.of());
            return new AdminItem(sanitize(u),
                    roles.stream().map(SysRole::getCode).toList(),
                    roles.stream().map(SysRole::getName).toList());
        });
    }

    /** 批量加载多个用户的角色（2 次 SQL：关联表 + 角色表）。 */
    private Map<Long, List<SysRole>> batchRolesOf(List<Long> userIds) {
        Map<Long, List<SysRole>> result = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        List<SysUserRole> relations = userRoleMapper.findByUserIds(userIds);
        List<Long> roleIds = relations.stream().map(SysUserRole::getRoleId).distinct().toList();
        Map<Long, SysRole> roleById = new LinkedHashMap<>();
        if (!roleIds.isEmpty()) {
            roleMapper.selectBatchIds(roleIds).forEach(r -> roleById.put(r.getId(), r));
        }
        for (SysUserRole relation : relations) {
            SysRole role = roleById.get(relation.getRoleId());
            if (role != null) {
                result.computeIfAbsent(relation.getUserId(), k -> new java.util.ArrayList<>()).add(role);
            }
        }
        return result;
    }

    /** 脱敏：只暴露展示字段，杜绝密码哈希/refreshToken 泄漏。 */
    private Map<String, Object> sanitize(SysUser u) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", u.getId());
        result.put("username", u.getUsername());
        result.put("nickname", u.getNickname());
        result.put("avatar", u.getAvatar());
        result.put("status", u.getStatus());
        result.put("mustChangePassword", u.getMustChangePassword());
        result.put("lastLoginAt", u.getLastLoginAt());
        result.put("createdAt", u.getCreatedAt());
        result.put("updatedAt", u.getUpdatedAt());
        return result;
    }

    @Transactional
    public SysUser create(String username, String password, String nickname, List<Long> roleIds) {
        // 先归一化再做唯一性检查（与入库值一致，防止 " admin" 绕过检查后撞唯一索引）
        String normalizedUsername = username.trim();
        if (userMapper.existsByUsername(normalizedUsername)) {
            throw BusinessException.conflict("用户名已存在");
        }
        ValidatedRoles requestedRoles = validateRoles(roleIds, true);
        if (requestedRoles.includesSuperAdmin()) {
            throw BusinessException.conflict("系统只允许一个超级管理员，不能分配 SUPER_ADMIN 角色");
        }
        SysUser user = SysUser.builder()
                .username(normalizedUsername)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .status(1)
                .mustChangePassword(1)
                .failedLoginAttempts(0)
                .build();
        userMapper.insert(user);
        replaceRoles(user.getId(), requestedRoles.ids());
        return user;
    }

    @Transactional
    public void update(Long id, String nickname, Integer status, List<Long> roleIds, SysUser operator) {
        SysUser user = getOrThrow(id);
        List<Long> currentRoleIds = roleIdsOf(user);
        boolean targetIsSuperAdmin = isSuperAdmin(user);
        ValidatedRoles requestedRoles = roleIds == null ? null : validateRoles(roleIds, true);

        // 唯一超管是系统根账号：任何操作者都不能禁用它，也不能改变它的角色集合。
        if (targetIsSuperAdmin) {
            if (status != null && status != 1) {
                throw BusinessException.badRequest("超级管理员账号不可禁用");
            }
            if (requestedRoles != null && !sameRoleIds(requestedRoles.ids(), currentRoleIds)) {
                throw BusinessException.badRequest("超级管理员角色不可修改");
            }
        } else if (requestedRoles != null && requestedRoles.includesSuperAdmin()) {
            throw BusinessException.conflict("系统只允许一个超级管理员，不能分配 SUPER_ADMIN 角色");
        }

        if (Objects.equals(id, operator.getId())) {
            // 不能改自己角色/禁用自己
            if (status != null && status != 1) {
                throw BusinessException.badRequest("不能禁用自己");
            }
            if (requestedRoles != null && !sameRoleIds(requestedRoles.ids(), currentRoleIds)) {
                throw BusinessException.badRequest("不能修改自己的角色");
            }
        }
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (status != null) {
            user.setStatus(status);
        }
        if (requestedRoles != null) {
            replaceRoles(id, requestedRoles.ids());
        }
        userMapper.updateById(user);
        // 角色集合变更 → 该用户权限缓存失效（§6.3 失效表，安全边界 R4）
        if (requestedRoles != null) {
            permissionService.evictPermissionCache(id);
        }
        // 禁用账号 → 清掉其所有刷新令牌（§4.6 点 2，Redis 唯一权威）
        if (status != null && status == 0) {
            refreshTokenStore.deleteAllForUser(id);
        }
    }

    @Transactional
    public void delete(Long id, SysUser operator) {
        if (Objects.equals(id, operator.getId())) {
            throw BusinessException.badRequest("不能删除自己");
        }
        SysUser user = getOrThrow(id);
        if (isSuperAdmin(user)) {
            throw BusinessException.badRequest("SUPER_ADMIN 账号不可删除");
        }
        userRoleMapper.deleteByIdUserId(id);
        userMapper.deleteById(user.getId());
        // 删除管理员 → 其权限缓存失效（账号实时 selectById 已即时 401，此处清残留缓存）
        permissionService.evictPermissionCache(id);
        // 删除账号 → 清掉其所有刷新令牌（§4.6 点 2）
        refreshTokenStore.deleteAllForUser(id);
    }

    /** 个人中心：昵称/头像 */
    @Transactional
    public void updateProfile(SysUser user, String nickname, String avatar) {
        if (nickname != null && !nickname.isBlank()) {
            user.setNickname(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
    }

    /** 改密：校验旧密码 → 全量失效 refreshToken（其他会话立即退出）→ 清强制改密标记。 */
    @Transactional
    public void changePassword(SysUser user, String oldPassword, String newPassword) {
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        if (newPassword.length() < 8) {
            throw BusinessException.badRequest("新密码至少 8 位");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(0);
        userMapper.updateById(user);
        // 改密 → 全量失效刷新令牌（其他会话立即退出，Redis 唯一权威 §4.6 点 2）
        refreshTokenStore.deleteAllForUser(user.getId());
    }

    public Map<String, Object> profile(SysUser user) {
        // avatar 可能为 null，Map.of 不允许 null 值，用 LinkedHashMap
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        result.put("roles", rolesOf(user.getId()));
        result.put("permissions", permissionService.loadForUser(user).permissions());
        return result;
    }

    private SysUser getOrThrow(Long id) {
        return Optional.ofNullable(userMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("管理员不存在"));
    }

    private boolean isSuperAdmin(SysUser user) {
        return permissionService.loadForUser(user).roles().stream()
                .anyMatch(r -> AdminPermissionService.SUPER_ADMIN.equals(r.getCode()));
    }

    private List<Long> roleIdsOf(SysUser user) {
        return userRoleMapper.findByIdUserId(user.getId()).stream()
                .map(ur -> ur.getRoleId()).toList();
    }

    private List<String> rolesOf(Long userId) {
        List<Long> roleIds = userRoleMapper.findByIdUserId(userId).stream()
                .map(ur -> ur.getRoleId()).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream().map(SysRole::getCode).toList();
    }

    private ValidatedRoles validateRoles(List<Long> roleIds, boolean requireAtLeastOne) {
        if (roleIds == null || (requireAtLeastOne && roleIds.isEmpty())) {
            throw BusinessException.badRequest("至少分配一个角色");
        }
        if (roleIds.stream().anyMatch(Objects::isNull)) {
            throw BusinessException.badRequest("角色不能为空");
        }

        List<Long> normalizedIds = List.copyOf(new LinkedHashSet<>(roleIds));
        if (normalizedIds.isEmpty()) {
            return new ValidatedRoles(normalizedIds, false);
        }
        List<SysRole> roles = roleMapper.selectBatchIds(normalizedIds);
        Set<Long> existingIds = roles.stream().map(SysRole::getId).collect(java.util.stream.Collectors.toSet());
        for (Long roleId : normalizedIds) {
            if (!existingIds.contains(roleId)) {
                throw BusinessException.badRequest("角色不存在: " + roleId);
            }
        }
        boolean includesSuperAdmin = roles.stream()
                .anyMatch(role -> AdminPermissionService.SUPER_ADMIN.equals(role.getCode()));
        return new ValidatedRoles(normalizedIds, includesSuperAdmin);
    }

    private boolean sameRoleIds(List<Long> left, List<Long> right) {
        return Set.copyOf(left).equals(Set.copyOf(right));
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.deleteByIdUserId(userId);
        for (Long roleId : roleIds) {
            userRoleMapper.insert(new SysUserRole(userId, roleId));
        }
    }

    private record ValidatedRoles(List<Long> ids, boolean includesSuperAdmin) {
    }
}
