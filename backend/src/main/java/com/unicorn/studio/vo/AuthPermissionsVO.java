package com.unicorn.studio.vo;

import com.unicorn.studio.model.SysRole;

import java.util.List;
import java.util.Set;

/**
 * 后台认证权限缓存 VO（Redis 方案 §6.3 / B10）。
 * <p>
 * 缓存 {@code cache:auth:perm:{userId}} 的 value：角色（code + name，供 ROLE_ authority 与 /auth/me 展示）
 * + 权限码集合。刻意只放展示所需字段，不含 {@link SysRole} 实体的后台字段（description/builtin/status/审计字段），
 * 也不含 {@code SysUser} 的敏感字段（password/refreshToken）。
 */
public record AuthPermissionsVO(List<RoleRef> roles, List<String> permissions) {

    public record RoleRef(String code, String name) {
    }

    public static AuthPermissionsVO from(List<SysRole> roles, Set<String> permissions) {
        return new AuthPermissionsVO(
                roles.stream().map(r -> new RoleRef(r.getCode(), r.getName())).toList(),
                List.copyOf(permissions));
    }
}
