package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.ServiceItemAdminService;
import com.unicorn.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ServiceItemAdminControllerTest {

    private final ServiceItemAdminController controller =
            new ServiceItemAdminController(mock(ServiceItemAdminService.class));

    @Test
    void listRejectsPageBelowOne() {
        assertThrows(BusinessException.class, () -> controller.list(0, 10));
    }

    @Test
    void listRejectsSizeOutsideSupportedRange() {
        assertThrows(BusinessException.class, () -> controller.list(1, 101));
    }
}
