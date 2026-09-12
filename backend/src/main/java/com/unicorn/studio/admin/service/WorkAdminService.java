package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.mapper.WorkMapper;
import com.unicorn.studio.model.SiteSettings;
import com.unicorn.studio.model.Work;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 作品管理（实施方案 §3.3）：CRUD / 状态机 / 拖拽排序 / 删除级联清评论。
 * 注意：富文本 content 保存前消毒（jsoup 白名单 + img 域名白名单）。
 */
@Service
public class WorkAdminService {

    private static final String SEO_GROUP = "seo";
    private static final String SEO_KEY_PREFIX = "seo.work.";

    private final WorkMapper workMapper;
    private final CommentMapper commentMapper;
    private final SiteSettingsMapper settingsMapper;
    private final CacheHelper cache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WorkAdminService(WorkMapper workMapper,
                            CommentMapper commentMapper,
                            SiteSettingsMapper settingsMapper,
                            CacheHelper cache) {
        this.workMapper = workMapper;
        this.commentMapper = commentMapper;
        this.settingsMapper = settingsMapper;
        this.cache = cache;
    }

    public PageResult<Work> list(String keyword, String category, String status, int page, int size) {
        LambdaQueryWrapper<Work> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Work::getTitle, like).or().like(Work::getSlug, like));
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(Work::getCategory, category);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Work::getStatus, status);
        }
        wrapper.orderByAsc(Work::getSortOrder, Work::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        return PageResult.of(workMapper.selectPage(new Page<>(page + 1, size), wrapper));
    }

    public Map<String, Object> detail(Long id) {
        Work work = getOrThrow(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("work", work);
        result.put("seo", loadSeo(work.getId()));
        return result;
    }

    @Transactional
    public Work create(WorkForm form) {
        if (form.slug() == null || form.slug().isBlank()) {
            throw BusinessException.badRequest("slug 不能为空");
        }
        if (workMapper.findBySlug(form.slug().trim()).isPresent()) {
            throw BusinessException.conflict("slug 已存在");
        }
        Work work = Work.builder()
                .slug(form.slug().trim())
                .title(form.title())
                .category(form.category())
                .coverUrl(form.coverUrl())
                .summary(form.summary())
                .content(HtmlSanitizer.clean(form.content()))
                .clientName(form.clientName())
                .externalLink(form.externalLink())
                .featured(Boolean.TRUE.equals(form.featured()) ? 1 : 0)
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(form.status() == null ? "draft" : form.status())
                .viewCount(0)
                .build();
        if ("published".equals(work.getStatus())) {
            work.setPublishedAt(LocalDateTime.now());
        }
        workMapper.insert(work);
        saveSeo(work.getId(), form.seo());
        // 穿透防护（§4.1 P1 要点 3）：新建后清空该 slug 的空值哨兵，否则「刚发的作品 404」
        cache.evictAfterCommit(CacheKeys.nullWork(work.getSlug()));
        return work;
    }

    @Transactional
    public void update(Long id, WorkForm form) {
        Work work = getOrThrow(id);
        if (form.slug() != null && !form.slug().isBlank() && !form.slug().trim().equals(work.getSlug())) {
            String newSlug = form.slug().trim();
            // 与 create 对齐的唯一性校验，避免撞唯一索引落 500
            if (workMapper.findBySlug(newSlug).isPresent()) {
                throw BusinessException.conflict("slug 已存在");
            }
            work.setSlug(newSlug);
        }
        if (form.title() != null) {
            work.setTitle(form.title());
        }
        if (form.category() != null) {
            work.setCategory(form.category());
        }
        if (form.coverUrl() != null) {
            work.setCoverUrl(form.coverUrl());
        }
        if (form.summary() != null) {
            work.setSummary(form.summary());
        }
        if (form.content() != null) {
            work.setContent(HtmlSanitizer.clean(form.content()));
        }
        if (form.clientName() != null) {
            work.setClientName(form.clientName());
        }
        if (form.externalLink() != null) {
            work.setExternalLink(form.externalLink());
        }
        if (form.featured() != null) {
            work.setFeatured(form.featured() ? 1 : 0);
        }
        if (form.sortOrder() != null) {
            work.setSortOrder(form.sortOrder());
        }
        if (form.status() != null && !form.status().equals(work.getStatus())) {
            changeStatus(work, form.status());
        }
        workMapper.updateById(work);
        saveSeo(id, form.seo());
        // 发布/改 slug 后清空该 slug 的空值哨兵（§4.1 P1 要点 3）
        cache.evictAfterCommit(CacheKeys.nullWork(work.getSlug()));
    }

    /** 状态机：draft/published/offline；发布时补 publishedAt。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        Work work = getOrThrow(id);
        changeStatus(work, status);
        workMapper.updateById(work);
    }

    /** 拖拽排序：ids 为全量 id 按新顺序排列；缺省的 id 追加到末尾（保持原相对顺序）。 */
    @Transactional
    public void sort(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Work> all = workMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, Work> byId = new LinkedHashMap<>();
        Map<Long, Integer> oldOrders = new LinkedHashMap<>();
        for (Work w : all) {
            byId.put(w.getId(), w);
            oldOrders.put(w.getId(), w.getSortOrder());
        }
        int order = 0;
        for (Long id : ids) {
            Work w = byId.remove(id);
            if (w != null) {
                w.setSortOrder(order++);
            }
        }
        for (Work w : byId.values()) {
            w.setSortOrder(order++);
        }
        // 仅更新 sortOrder 实际变化的行，避免全表无条件 UPDATE
        for (Work w : all) {
            if (!java.util.Objects.equals(oldOrders.get(w.getId()), w.getSortOrder())) {
                workMapper.updateById(w);
            }
        }
    }

    /** 删除作品 + 同步清理关联评论（多态关联无外键，实施方案 §3.4）。 */
    @Transactional
    public void delete(Long id) {
        Work work = getOrThrow(id);
        commentMapper.deleteByTargetTypeAndTargetId("work", id);
        settingsMapper.findBySettingKey(seoKey(id)).ifPresent(s -> settingsMapper.deleteById(s.getId()));
        workMapper.deleteById(work.getId());
    }

    private void changeStatus(Work work, String status) {
        if (!List.of("draft", "published", "offline").contains(status)) {
            throw BusinessException.badRequest("非法状态: " + status);
        }
        work.setStatus(status);
        if ("published".equals(status)) {
            work.setPublishedAt(LocalDateTime.now());
        }
    }

    private Work getOrThrow(Long id) {
        return Optional.ofNullable(workMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("作品不存在"));
    }

    // ---- per-entity SEO：存 site_settings（seo 组，key=seo.work.{id}） ----

    private void saveSeo(Long workId, Map<String, String> seo) {
        if (seo == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(seo);
            SiteSettings setting = settingsMapper.findBySettingKey(seoKey(workId))
                    .orElseGet(() -> SiteSettings.builder()
                            .groupName(SEO_GROUP)
                            .settingKey(seoKey(workId))
                            .description("作品 SEO：" + workId)
                            .build());
            setting.setSettingValue(json);
            if (setting.getId() == null) {
                settingsMapper.insert(setting);
            } else {
                settingsMapper.updateById(setting);
            }
        } catch (Exception e) {
            throw BusinessException.badRequest("SEO 数据格式不正确");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadSeo(Long workId) {
        return settingsMapper.findBySettingKey(seoKey(workId))
                .map(s -> {
                    try {
                        return objectMapper.readValue(s.getSettingValue(), Map.class);
                    } catch (Exception e) {
                        return Map.of();
                    }
                })
                .orElse(Map.of());
    }

    private String seoKey(Long workId) {
        return SEO_KEY_PREFIX + workId;
    }

    /** 作品表单（controller record 复用）。 */
    public record WorkForm(String title, String slug, String category, String coverUrl,
                           String summary, String content, String clientName, String externalLink,
                           Boolean featured, Integer sortOrder, String status,
                           Map<String, String> seo) {
    }
}
