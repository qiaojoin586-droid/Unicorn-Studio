package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理员 CRUD（实施方案 §3.6）：
 * - 不能删自己；不能禁用/改自己角色；SUPER_ADMIN 账号不可删；
 * - 列表带角色名；编辑角色时重建关联。
 */
@Service
public class AdminAccountService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminPermissionService permissionService;

    public AdminAccountService(SysUserMapper userMapper,
                               SysRoleMapper roleMapper,
                               SysUserRoleMapper userRoleMapper,
                               PasswordEncoder passwordEncoder,
                               AdminPermissionService permissionService) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.permissionService = permissionService;
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
        return PageResult.of(userMapper.selectPage(new Page<>(page + 1, size), wrapper),
                u -> new AdminItem(sanitize(u), rolesOf(u.getId()), roleNamesOf(u.getId())));
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
        if (userMapper.existsByUsername(username)) {
            throw BusinessException.conflict("用户名已存在");
        }
        if (roleIds == null || roleIds.isEmpty()) {
            throw BusinessException.badRequest("至少分配一个角色");
        }
        SysUser user = SysUser.builder()
                .username(username.trim())
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .status(1)
                .mustChangePassword(1)
                .failedLoginAttempts(0)
                .build();
        userMapper.insert(user);
        replaceRoles(user.getId(), roleIds);
        return user;
    }

    @Transactional
    public void update(Long id, String nickname, Integer status, List<Long> roleIds, SysUser operator) {
        SysUser user = getOrThrow(id);
        if (Objects.equals(id, operator.getId())) {
            // 不能改自己角色/禁用自己
            if (status != null && status == 0) {
                throw BusinessException.badRequest("不能禁用自己");
            }
            if (roleIds != null && !roleIds.equals(roleIdsOf(user))) {
                throw BusinessException.badRequest("不能修改自己的角色");
            }
        }
        if (nickname != null) {
            user.setNickname(nickname);
        }
        if (status != null) {
            user.setStatus(status);
        }
        if (roleIds != null) {
            replaceRoles(id, roleIds);
        }
        userMapper.updateById(user);
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
        user.setRefreshToken(null);
        user.setRefreshTokenExpiresAt(null);
        user.setMustChangePassword(0);
        userMapper.updateById(user);
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

    private List<String> roleNamesOf(Long userId) {
        List<Long> roleIds = userRoleMapper.findByIdUserId(userId).stream()
                .map(ur -> ur.getRoleId()).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream().map(SysRole::getName).toList();
    }

    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.deleteByIdUserId(userId);
        for (Long roleId : roleIds) {
            if (roleMapper.selectById(roleId) == null) {
                throw BusinessException.badRequest("角色不存在: " + roleId);
            }
            userRoleMapper.insert(new SysUserRole(userId, roleId));
        }
    }
}
