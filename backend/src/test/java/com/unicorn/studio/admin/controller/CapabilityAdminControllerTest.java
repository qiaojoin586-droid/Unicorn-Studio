package com.unicorn.studio.admin.controller;

import com.unicorn.studio.admin.service.CapabilityAdminService;
import com.unicorn.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CapabilityAdminControllerTest {

    private final CapabilityAdminController controller =
            new CapabilityAdminController(mock(CapabilityAdminService.class));

    @Test
    void listRejectsPageBelowOne() {
        assertThrows(BusinessException.class, () -> controller.list(0, 10));
    }

    @Test
    void listRejectsSizeOutsideSupportedRange() {
        assertThrows(BusinessException.class, () -> controller.list(1, 101));
    }
}
