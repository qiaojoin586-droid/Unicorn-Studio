package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.mapper.SysPermissionMapper;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.SysUserRole;
import com.unicorn.studio.vo.AuthPermissionsVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限加载：用户角色 + 权限码集合。
 * SUPER_ADMIN 角色加载全量权限码（代码级绕过）；其余角色实时 join 查询，修改即时生效。
 * <p>
 * 两级缓存（Redis 方案 §6.3 / B10）：
 * <ol>
 *   <li><b>跨请求</b>：{@link #loadPermissionsCached} 走 Redis {@code cache:auth:perm:{userId}}（TTL 10 min），
 *       未命中才查库；写路径（RoleService / AdminAccountService）通过 {@link #evictPermissionCache} /
 *       {@link #evictPermissionCacheForRole} 失效，另保留 {@link #evictAllPermissionCache} 兜底；</li>
 *   <li><b>请求内</b>：{@code loadForUser} 的 ThreadLocal 缓存，避免同请求重复查库（§2.5 / §11 P-1），
 *       由 {@link com.unicorn.studio.admin.security.JwtAuthFilter} 在 finally 清理。</li>
 * </ol>
 * <b>安全边界（R4）</b>：账号「状态」不缓存 —— {@code JwtAuthFilter} 每次实时 {@code selectById} 校验
 * {@code status==1}，禁用/删除即时 401，不引入越权窗口。
 */
@Service
public class AdminPermissionService {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** 权限缓存 key 前缀（§5.1 键命名规范） */
    public static final String PERM_CACHE_PREFIX = "cache:auth:perm:";
    private static final Duration PERM_TTL = Duration.ofMinutes(10);

    /** 用户的角色与权限码集合 */
    public record LoadResult(List<SysRole> roles, Set<String> permissions) {
    }

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final CacheHelper cache;

    /** 请求级缓存：key=userId；请求结束由 JwtAuthFilter 清理，避免线程池复用串数据。 */
    private final ThreadLocal<Map<Long, LoadResult>> requestCache = ThreadLocal.withInitial(HashMap::new);

    public AdminPermissionService(SysUserRoleMapper userRoleMapper,
                                  SysRoleMapper roleMapper,
                                  SysPermissionMapper permissionMapper,
                                  CacheHelper cache) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.cache = cache;
    }

    public LoadResult loadForUser(SysUser user) {
        Map<Long, LoadResult> cache = requestCache.get();
        LoadResult cached = cache.get(user.getId());
        if (cached != null) {
            return cached;
        }
        LoadResult result = doLoad(user);
        cache.put(user.getId(), result);
        return result;
    }

    /** 请求结束清理 ThreadLocal（安全边界：禁止跨请求复用）。 */
    public void clearRequestCache() {
        requestCache.remove();
    }

    /** 跨请求缓存的权限快照（Redis，TTL 10 min）；未命中回源并写缓存。 */
    public AuthPermissionsVO loadPermissionsCached(SysUser user) {
        String key = PERM_CACHE_PREFIX + user.getId();
        return cache.get(key, AuthPermissionsVO.class).orElseGet(() -> {
            LoadResult result = loadForUser(user);
            AuthPermissionsVO vo = AuthPermissionsVO.from(result.roles(), result.permissions());
            cache.set(key, vo, PERM_TTL);
            return vo;
        });
    }

    /** 失效单个用户的权限缓存（账号角色变更/删除）。 */
    public void evictPermissionCache(Long userId) {
        cache.evictAfterCommit(PERM_CACHE_PREFIX + userId);
    }

    /** 失效某个角色下所有用户的权限缓存（角色权限/名称变更）。 */
    public void evictPermissionCacheForRole(Long roleId) {
        List<Long> userIds = userRoleMapper.findByRoleId(roleId).stream()
                .map(SysUserRole::getUserId)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        cache.evictAfterCommit(userIds.stream()
                .map(id -> PERM_CACHE_PREFIX + id)
                .toArray(String[]::new));
    }

    /** 兜底：清空全部权限缓存（§6.3 硬性要求 3 的应急运维入口）。 */
    public void evictAllPermissionCache() {
        cache.deleteByPattern(PERM_CACHE_PREFIX + "*");
    }

    private LoadResult doLoad(SysUser user) {
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
