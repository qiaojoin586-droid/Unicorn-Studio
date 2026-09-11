package com.unicorn.studio.admin.service;

import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.mapper.ServiceItemMapper;
import com.unicorn.studio.model.ServiceItem;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceItemAdminServiceTest {

    @Mock
    private ServiceItemMapper serviceItemMapper;
    @Mock
    private CacheHelper cache;

    private ServiceItemAdminService service;
    private Validator validator;

    @BeforeEach
    void setUp() {
        service = new ServiceItemAdminService(serviceItemMapper, cache);
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void createDefaultsToPublishedAndNormalizesText() {
        ServiceItemAdminService.ServiceItemForm form = new ServiceItemAdminService.ServiceItemForm(
                "  AI 应用开发  ", "  构建真实项目  ", "  code  ", null, null);

        service.create(form);

        ArgumentCaptor<ServiceItem> captor = ArgumentCaptor.forClass(ServiceItem.class);
        verify(serviceItemMapper).insert(captor.capture());
        ServiceItem saved = captor.getValue();
        assertEquals("AI 应用开发", saved.getTitle());
        assertEquals("构建真实项目", saved.getDescription());
        assertEquals("code", saved.getIcon());
        assertEquals(0, saved.getSortOrder());
        assertEquals("published", saved.getStatus());
    }

    @Test
    void createMapsDisabledToOfflineAndBlankIconToNull() {
        ServiceItemAdminService.ServiceItemForm form = new ServiceItemAdminService.ServiceItemForm(
                "后端开发", "Java 与服务端工程", "   ", 3, false);

        service.create(form);

        ArgumentCaptor<ServiceItem> captor = ArgumentCaptor.forClass(ServiceItem.class);
        verify(serviceItemMapper).insert(captor.capture());
        ServiceItem saved = captor.getValue();
        assertNull(saved.getIcon());
        assertEquals(3, saved.getSortOrder());
        assertEquals("offline", saved.getStatus());
    }

    @Test
    void updateLoadsFullEntityAndKeepsOptionalDefaults() {
        ServiceItem existing = ServiceItem.builder()
                .id(7L)
                .title("旧标题")
                .description("旧说明")
                .icon("old")
                .sortOrder(8)
                .status("offline")
                .build();
        when(serviceItemMapper.selectById(7L)).thenReturn(existing);
        ServiceItemAdminService.ServiceItemForm form = new ServiceItemAdminService.ServiceItemForm(
                "新标题", "新说明", null, null, null);

        service.update(7L, form);

        ArgumentCaptor<ServiceItem> captor = ArgumentCaptor.forClass(ServiceItem.class);
        verify(serviceItemMapper).updateById(captor.capture());
        ServiceItem updated = captor.getValue();
        assertEquals("新标题", updated.getTitle());
        assertEquals("新说明", updated.getDescription());
        assertNull(updated.getIcon());
        assertEquals(8, updated.getSortOrder());
        assertEquals("offline", updated.getStatus());
    }

    @Test
    void updateRejectsStatusOutsideWhitelist() {
        ServiceItem existing = ServiceItem.builder()
                .id(7L)
                .title("旧标题")
                .description("旧说明")
                .sortOrder(1)
                .status("draft")
                .build();
        when(serviceItemMapper.selectById(7L)).thenReturn(existing);
        ServiceItemAdminService.ServiceItemForm form = new ServiceItemAdminService.ServiceItemForm(
                "新标题", "新说明", null, null, null);

        assertThrows(BusinessException.class, () -> service.update(7L, form));
    }

    @Test
    void formValidationMatchesDatabaseLimits() {
        ServiceItemAdminService.ServiceItemForm invalid = new ServiceItemAdminService.ServiceItemForm(
                " ", "", "x".repeat(51), -1, true);

        Set<ConstraintViolation<ServiceItemAdminService.ServiceItemForm>> violations = validator.validate(invalid);

        assertEquals(4, violations.size());
    }
}
