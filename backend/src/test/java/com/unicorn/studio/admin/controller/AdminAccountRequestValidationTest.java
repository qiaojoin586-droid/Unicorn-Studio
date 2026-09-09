package com.unicorn.studio.admin.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminAccountRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void createRequiresAtLeastOneRole() {
        AdminAccountController.CreateAdminRequest request =
                new AdminAccountController.CreateAdminRequest(
                        "editor", "password123", "编辑", List.of());

        assertEquals("至少分配一个角色", validator.validate(request).iterator().next().getMessage());
    }

    @Test
    void updateRejectsInvalidStatusAndEmptyRoles() {
        AdminAccountController.UpdateAdminRequest request =
                new AdminAccountController.UpdateAdminRequest("编辑", 2, List.of());

        assertEquals(2, validator.validate(request).size());
    }
}
