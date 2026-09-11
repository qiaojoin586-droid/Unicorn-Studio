package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.SysPermissionMapper;
import com.unicorn.studio.mapper.SysRoleMapper;
import com.unicorn.studio.mapper.SysRolePermissionMapper;
import com.unicorn.studio.mapper.SysUserRoleMapper;
import com.unicorn.studio.model.SysRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysRolePermissionMapper rolePermissionMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysPermissionMapper permissionMapper;
    @Mock
    private AdminPermissionService permissionService;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(roleMapper, rolePermissionMapper, userRoleMapper,
                permissionMapper, permissionService);
    }

    @Test
    void superAdminCodeIsReservedEvenWithDifferentCaseAndWhitespace() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.create("  super_admin  ", "另一个超管", null, List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }
}
