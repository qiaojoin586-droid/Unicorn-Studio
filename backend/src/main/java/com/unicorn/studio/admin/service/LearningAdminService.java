package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.LearningResourceMapper;
import com.unicorn.studio.model.LearningResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 后台学习资料管理：分页列表（分类/状态/关键词）/ 新增 / 编辑 / 上下架 / 单个与批量删除。 */
@Service
public class LearningAdminService {

    private final LearningResourceMapper resourceMapper;
    private final CacheHelper cache;

    public LearningAdminService(LearningResourceMapper resourceMapper, CacheHelper cache) {
        this.resourceMapper = resourceMapper;
        this.cache = cache;
    }

    public PageResult<LearningResource> list(String category, Integer status, String keyword,
                                             int page, int size) {
        LambdaQueryWrapper<LearningResource> wrapper = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            wrapper.eq(LearningResource::getCategory, category.trim());
        }
        if (status != null) {
            wrapper.eq(LearningResource::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(LearningResource::getTitle, like)
                    .or().like(LearningResource::getDescription, like)
                    .or().like(LearningResource::getTags, like));
        }
        wrapper.orderByAsc(LearningResource::getSortOrder, LearningResource::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        return PageResult.of(resourceMapper.selectPage(PageResult.safePage(page + 1, size), wrapper));
    }

    @Transactional
    public LearningResource create(LearningForm form) {
        validate(form);
        LearningResource resource = LearningResource.builder()
                .title(form.title().trim())
                .description(form.description().trim())
                .content(HtmlSanitizer.clean(form.content()))
                .category(trimToNull(form.category()))
                .tags(trimToNull(form.tags()))
                .coverUrl(trimToNull(form.coverUrl()))
                .attachmentUrl(trimToNull(form.attachmentUrl()))
                .url(trimToNull(form.url()))
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(Boolean.TRUE.equals(form.enabled()) ? 1 : 0)
                .build();
        resourceMapper.insert(resource);
        evictListCache();
        cache.evictAfterCommit(CacheKeys.nullLearning(resource.getId()));
        return resource;
    }

    @Transactional
    public void update(Long id, LearningForm form) {
        LearningResource resource = getOrThrow(id);
        if (form.title() != null && !form.title().isBlank()) {
            resource.setTitle(form.title().trim());
        }
        if (form.description() != null && !form.description().isBlank()) {
            resource.setDescription(form.description().trim());
        }
        if (form.content() != null) {
            resource.setContent(HtmlSanitizer.clean(form.content()));
        }
        if (form.category() != null) {
            resource.setCategory(trimToNull(form.category()));
        }
        if (form.tags() != null) {
            resource.setTags(trimToNull(form.tags()));
        }
        if (form.coverUrl() != null) {
            resource.setCoverUrl(trimToNull(form.coverUrl()));
        }
        if (form.attachmentUrl() != null) {
            resource.setAttachmentUrl(trimToNull(form.attachmentUrl()));
        }
        if (form.url() != null) {
            resource.setUrl(trimToNull(form.url()));
        }
        if (form.sortOrder() != null) {
            resource.setSortOrder(form.sortOrder());
        }
        if (form.enabled() != null) {
            resource.setStatus(form.enabled() ? 1 : 0);
        }
        resourceMapper.updateById(resource);
        evictListCache();
        cache.evictAfterCommit(CacheKeys.nullLearning(id));
    }

    /** 上下架：1 上架 / 0 下架。 */
    @Transactional
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.badRequest("非法状态: " + status);
        }
        LearningResource resource = getOrThrow(id);
        resource.setStatus(status);
        resourceMapper.updateById(resource);
        evictListCache();
        cache.evictAfterCommit(CacheKeys.nullLearning(id));
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        resourceMapper.deleteById(id);
        evictListCache();
        cache.evictAfterCommit(CacheKeys.nullLearning(id));
    }

    /** 批量删除。 */
    @Transactional
    public Map<String, Object> batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.badRequest("ids 不能为空");
        }
        int deleted = resourceMapper.deleteBatchIds(ids);
        evictListCache();
        cache.evictAfterCommit(ids.stream().map(CacheKeys::nullLearning).toArray(String[]::new));
        return Map.of("successCount", deleted, "failCount", ids.size() - deleted);
    }

    /** 学习资料列表缓存失效（§5.3 陷阱 4：LearningAdminService.* → cache:learning:list）。 */
    private void evictListCache() {
        cache.evictAfterCommit(CacheKeys.LEARNING_LIST);
    }

    private void validate(LearningForm form) {
        if (form.title() == null || form.title().isBlank()) {
            throw BusinessException.badRequest("标题不能为空");
        }
        if (form.description() == null || form.description().isBlank()) {
            throw BusinessException.badRequest("描述不能为空");
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private LearningResource getOrThrow(Long id) {
        return Optional.ofNullable(resourceMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("资料不存在"));
    }

    /** 资料表单（controller record 复用，模式同 CarouselForm）。 */
    public record LearningForm(String title, String description, String content, String category,
                               String tags, String coverUrl, String attachmentUrl, String url,
                               Integer sortOrder, Boolean enabled) {
    }
}
