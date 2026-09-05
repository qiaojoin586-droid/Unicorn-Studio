package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.mapper.CapabilityMapper;
import com.unicorn.studio.model.Capability;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/** 我们培养的能力后台管理。 */
@Service
public class CapabilityAdminService {

    private static final String PUBLISHED = "published";
    private static final String OFFLINE = "offline";
    private static final Set<String> ALLOWED_STATUSES = Set.of(PUBLISHED, OFFLINE);

    private final CapabilityMapper capabilityMapper;

    public CapabilityAdminService(CapabilityMapper capabilityMapper) {
        this.capabilityMapper = capabilityMapper;
    }

    public PageResult<CapabilityView> list(int page, int size) {
        Page<Capability> result = capabilityMapper.selectPage(
                new Page<>(page + 1, size),
                new LambdaQueryWrapper<Capability>()
                        .orderByAsc(Capability::getSortOrder, Capability::getId));
        return PageResult.of(result, CapabilityView::from);
    }

    @Transactional
    public Capability create(CapabilityForm form) {
        Capability item = Capability.builder()
                .title(form.title().trim())
                .description(form.description().trim())
                .icon(normalizeIcon(form.icon()))
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(statusFromEnabled(form.enabled(), PUBLISHED))
                .build();
        validateStatus(item.getStatus());
        capabilityMapper.insert(item);
        return item;
    }

    @Transactional
    public void update(Long id, CapabilityForm form) {
        Capability item = getOrThrow(id);
        item.setTitle(form.title().trim());
        item.setDescription(form.description().trim());
        item.setIcon(normalizeIcon(form.icon()));
        if (form.sortOrder() != null) {
            item.setSortOrder(form.sortOrder());
        }
        item.setStatus(statusFromEnabled(form.enabled(), item.getStatus()));
        validateStatus(item.getStatus());
        capabilityMapper.updateById(item);
    }

    @Transactional
    public void delete(Long id) {
        Capability item = getOrThrow(id);
        capabilityMapper.deleteById(item.getId());
    }

    private Capability getOrThrow(Long id) {
        return Optional.ofNullable(capabilityMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("培养能力不存在"));
    }

    private String statusFromEnabled(Boolean enabled, String defaultStatus) {
        if (enabled == null) {
            return defaultStatus;
        }
        return enabled ? PUBLISHED : OFFLINE;
    }

    private void validateStatus(String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw BusinessException.badRequest("培养能力状态无效");
        }
    }

    private String normalizeIcon(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        return icon.trim();
    }

    public record CapabilityForm(
            @NotBlank(message = "能力名称不能为空")
            @Size(max = 100, message = "能力名称不能超过 100 个字符")
            String title,
            @NotBlank(message = "能力说明不能为空")
            @Size(max = 500, message = "能力说明不能超过 500 个字符")
            String description,
            @Size(max = 50, message = "图标不能超过 50 个字符")
            String icon,
            @Min(value = 0, message = "排序值不能小于 0")
            Integer sortOrder,
            Boolean enabled) {
    }

    public record CapabilityView(
            Long id,
            String title,
            String description,
            String icon,
            int sortOrder,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static CapabilityView from(Capability item) {
            return new CapabilityView(
                    item.getId(),
                    item.getTitle(),
                    item.getDescription(),
                    item.getIcon(),
                    item.getSortOrder(),
                    PUBLISHED.equals(item.getStatus()),
                    item.getCreatedAt(),
                    item.getUpdatedAt());
        }
    }
}
