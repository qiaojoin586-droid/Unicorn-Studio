package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.RefreshTokenStore;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysUserMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysRole;
import com.unicorn.studio.model.SysUser;
import com.unicorn.studio.model.SysUserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AdminPermissionService permissionService;
    @Mock
    private RefreshTokenStore refreshTokenStore;

    private AdminAccountService service;

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(userMapper, roleMapper, userRoleMapper,
                passwordEncoder, permissionService, refreshTokenStore);
    }

    @Test
    void createRejectsSuperAdminRole() {
        SysRole superAdmin = role(1L, AdminPermissionService.SUPER_ADMIN);
        when(userMapper.existsByUsername("second-root")).thenReturn(false);
        when(roleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(superAdmin));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("second-root", "password123", "第二超管", List.of(1L)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void createAllowsOrdinaryRoleAssignment() {
        SysRole editor = role(2L, "EDITOR");
        when(userMapper.existsByUsername("editor")).thenReturn(false);
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(editor));
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        doAnswer(invocation -> {
            invocation.getArgument(0, SysUser.class).setId(20L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));

        service.create("editor", "password123", "编辑", List.of(2L));

        verify(userRoleMapper).insert(argThat(relation ->
                relation.getUserId().equals(20L) && relation.getRoleId().equals(2L)));
    }

    @Test
    void updateCannotDisableSuperAdminEvenWhenOperatorIsAnotherUser() {
        SysUser target = user(1L, "admin", 1);
        SysUser operator = user(2L, "manager", 1);
        stubCurrentRoles(target, List.of(1L), List.of(role(1L, AdminPermissionService.SUPER_ADMIN)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(1L, null, 0, null, operator));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void updateCannotRemoveSuperAdminRole() {
        SysUser target = user(1L, "admin", 1);
        SysUser operator = user(1L, "admin", 1);
        SysRole superAdmin = role(1L, AdminPermissionService.SUPER_ADMIN);
        SysRole editor = role(2L, "EDITOR");
        stubCurrentRoles(target, List.of(1L), List.of(superAdmin));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(editor));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(1L, null, null, List.of(2L), operator));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(userRoleMapper, never()).deleteByIdUserId(1L);
    }

    @Test
    void updateCannotPromoteOrdinaryAdminToSuperAdmin() {
        SysUser target = user(2L, "editor", 1);
        SysUser operator = user(1L, "admin", 1);
        SysRole editor = role(2L, "EDITOR");
        SysRole superAdmin = role(1L, AdminPermissionService.SUPER_ADMIN);
        stubCurrentRoles(target, List.of(2L), List.of(editor));
        when(roleMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(superAdmin));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.update(2L, null, null, List.of(1L), operator));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(userRoleMapper, never()).deleteByIdUserId(2L);
    }

    @Test
    void updateAllowsSuperAdminToAssignOrdinaryRoles() {
        SysUser target = user(2L, "editor", 1);
        SysUser operator = user(1L, "admin", 1);
        SysRole oldRole = role(3L, "AUDITOR");
        SysRole editor = role(2L, "EDITOR");
        stubCurrentRoles(target, List.of(3L), List.of(oldRole));
        when(roleMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(editor));

        service.update(2L, null, 1, List.of(2L), operator);

        verify(userRoleMapper).deleteByIdUserId(2L);
        verify(userRoleMapper).insert(argThat(relation ->
                relation.getUserId().equals(2L) && relation.getRoleId().equals(2L)));
    }

    private void stubCurrentRoles(SysUser user, List<Long> roleIds, List<SysRole> roles) {
        when(userMapper.selectById(user.getId())).thenReturn(user);
        when(userRoleMapper.findByIdUserId(user.getId())).thenReturn(roleIds.stream()
                .map(roleId -> new SysUserRole(user.getId(), roleId))
                .toList());
        Set<String> permissions = Set.of();
        when(permissionService.loadForUser(user))
                .thenReturn(new AdminPermissionService.LoadResult(roles, permissions));
    }

    private SysUser user(Long id, String username, Integer status) {
        return SysUser.builder().id(id).username(username).status(status).build();
    }

    private SysRole role(Long id, String code) {
        return SysRole.builder().id(id).code(code).name(code).status(1).build();
    }
}
