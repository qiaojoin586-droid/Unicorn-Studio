package com.unicorn.studio.admin.security;

import com.unicorn.studio.admin.service.AdminPermissionService;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminBootstrap bootstrap;
    private SysRole superAdminRole;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap(userMapper, roleMapper, userRoleMapper,
                passwordEncoder, "configured-password");
        superAdminRole = SysRole.builder()
                .id(1L)
                .code(AdminPermissionService.SUPER_ADMIN)
                .builtin(1)
                .status(1)
                .build();
        when(roleMapper.findByCodeForUpdate(AdminPermissionService.SUPER_ADMIN))
                .thenReturn(superAdminRole);
    }

    @Test
    void failsFastWhenMultipleSuperAdminsAlreadyExist() {
        when(userRoleMapper.findByRoleId(1L)).thenReturn(List.of(
                new SysUserRole(10L, 1L), new SysUserRole(11L, 1L)));

        assertThrows(IllegalStateException.class, () -> bootstrap.run(null));

        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(SysUser.class));
    }

    @Test
    void reEnablesTheOnlySuperAdmin() {
        SysUser user = SysUser.builder().id(10L).username("admin").status(0).build();
        when(userRoleMapper.findByRoleId(1L)).thenReturn(List.of(new SysUserRole(10L, 1L)));
        when(userMapper.selectById(10L)).thenReturn(user);

        bootstrap.run(null);

        assertEquals(1, user.getStatus());
        verify(userMapper).updateById(user);
    }

    @Test
    void assignsSuperAdminRoleToExistingAdminWhenNoSuperAdminExists() {
        SysUser user = SysUser.builder().id(10L).username("admin").status(1).build();
        when(userRoleMapper.findByRoleId(1L)).thenReturn(List.of());
        when(userMapper.findByUsername("admin")).thenReturn(Optional.of(user));

        bootstrap.run(null);

        verify(userRoleMapper).insert(argThat(relation ->
                relation.getUserId().equals(10L) && relation.getRoleId().equals(1L)));
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(SysUser.class));
    }
}
