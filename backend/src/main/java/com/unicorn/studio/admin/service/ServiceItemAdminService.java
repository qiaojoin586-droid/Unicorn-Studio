package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.ServiceItemMapper;
import com.unicorn.studio.model.ServiceItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/** 主要招收方向后台管理。 */
@Service
public class ServiceItemAdminService {

    private static final String PUBLISHED = "published";
    private static final String OFFLINE = "offline";
    private static final Set<String> ALLOWED_STATUSES = Set.of(PUBLISHED, OFFLINE);

    private final ServiceItemMapper serviceItemMapper;
    private final CacheHelper cache;

    public ServiceItemAdminService(ServiceItemMapper serviceItemMapper, CacheHelper cache) {
        this.serviceItemMapper = serviceItemMapper;
        this.cache = cache;
    }

    public PageResult<ServiceItemView> list(int page, int size) {
        Page<ServiceItem> result = serviceItemMapper.selectPage(
                new Page<>(page + 1, size),
                new LambdaQueryWrapper<ServiceItem>()
                        .orderByAsc(ServiceItem::getSortOrder, ServiceItem::getId));
        return PageResult.of(result, ServiceItemView::from);
    }

    @Transactional
    public ServiceItem create(ServiceItemForm form) {
        ServiceItem item = ServiceItem.builder()
                .title(form.title().trim())
                .description(form.description().trim())
                .icon(normalizeIcon(form.icon()))
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(statusFromEnabled(form.enabled(), PUBLISHED))
                .build();
        validateStatus(item.getStatus());
        serviceItemMapper.insert(item);
        evictListCache();
        return item;
    }

    @Transactional
    public void update(Long id, ServiceItemForm form) {
        ServiceItem item = getOrThrow(id);
        item.setTitle(form.title().trim());
        item.setDescription(form.description().trim());
        item.setIcon(normalizeIcon(form.icon()));
        if (form.sortOrder() != null) {
            item.setSortOrder(form.sortOrder());
        }
        item.setStatus(statusFromEnabled(form.enabled(), item.getStatus()));
        validateStatus(item.getStatus());
        serviceItemMapper.updateById(item);
        evictListCache();
    }

    @Transactional
    public void delete(Long id) {
        ServiceItem item = getOrThrow(id);
        serviceItemMapper.deleteById(item.getId());
        evictListCache();
    }

    /** 首页列表缓存失效（§5.3 陷阱 4：ServiceItemAdminService.* → cache:home:services）。 */
    private void evictListCache() {
        cache.evictAfterCommit(CacheKeys.HOME_SERVICES);
    }

    private ServiceItem getOrThrow(Long id) {
        return Optional.ofNullable(serviceItemMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("主要招收方向不存在"));
    }

    private String statusFromEnabled(Boolean enabled, String defaultStatus) {
        if (enabled == null) {
            return defaultStatus;
        }
        return enabled ? PUBLISHED : OFFLINE;
    }

    private void validateStatus(String status) {
        if (!ALLOWED_STATUSES.contains(status)) {
            throw BusinessException.badRequest("主要招收方向状态无效");
        }
    }

    private String normalizeIcon(String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        return icon.trim();
    }

    public record ServiceItemForm(
            @NotBlank(message = "方向名称不能为空")
            @Size(max = 100, message = "方向名称不能超过 100 个字符")
            String title,
            @NotBlank(message = "方向说明不能为空")
            @Size(max = 500, message = "方向说明不能超过 500 个字符")
            String description,
            @Size(max = 50, message = "图标不能超过 50 个字符")
            String icon,
            @Min(value = 0, message = "排序值不能小于 0")
            Integer sortOrder,
            Boolean enabled) {
    }

    public record ServiceItemView(
            Long id,
            String title,
            String description,
            String icon,
            int sortOrder,
            boolean enabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static ServiceItemView from(ServiceItem item) {
            return new ServiceItemView(
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
