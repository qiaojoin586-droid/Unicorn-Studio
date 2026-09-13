package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.cache.CacheObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CommentMapper;
import com.unicorn.studio.mapper.NewsMapper;
import com.unicorn.studio.mapper.SiteSettingsMapper;
import com.unicorn.studio.model.News;
import com.unicorn.studio.model.SiteSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 新闻管理（模式同作品，authorId 默认当前管理员）。 */
@Service
public class NewsAdminService {

    private static final String SEO_GROUP = "seo";
    private static final String SEO_KEY_PREFIX = "seo.news.";

    private final NewsMapper newsMapper;
    private final CommentMapper commentMapper;
    private final SiteSettingsMapper settingsMapper;
    private final CacheHelper cache;
    private final ObjectMapper objectMapper = CacheObjectMapper.create();

    public NewsAdminService(NewsMapper newsMapper,
                            CommentMapper commentMapper,
                            SiteSettingsMapper settingsMapper,
                            CacheHelper cache) {
        this.newsMapper = newsMapper;
        this.commentMapper = commentMapper;
        this.settingsMapper = settingsMapper;
        this.cache = cache;
    }

    public PageResult<News> list(String keyword, String category, String status, int page, int size) {
        // 列投影（B-09）：列表排除 MEDIUMTEXT 的 content，理由同 WorkAdminService.list ——
        // 管理端列表视图不展示正文，编辑表单走 detail(id) 单独取完整实体。
        LambdaQueryWrapper<News> wrapper = new LambdaQueryWrapper<News>()
                .select(News.class, info -> !"content".equals(info.getProperty()));
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(News::getTitle, like).or().like(News::getSlug, like));
        }
        if (category != null && !category.isBlank()) {
            wrapper.eq(News::getCategory, category);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(News::getStatus, status);
        }
        wrapper.orderByAsc(News::getSortOrder, News::getId);
        // controller 已做 page-1（0-based），MP Page 为 1-based
        return PageResult.of(newsMapper.selectPage(PageResult.safePage(page + 1, size), wrapper));
    }

    public Map<String, Object> detail(Long id) {
        News news = getOrThrow(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("news", news);
        result.put("seo", loadSeo(news.getId()));
        return result;
    }

    @Transactional
    public News create(NewsForm form, Long authorId) {
        if (form.slug() == null || form.slug().isBlank()) {
            throw BusinessException.badRequest("slug 不能为空");
        }
        if (newsMapper.findBySlug(form.slug().trim()).isPresent()) {
            throw BusinessException.conflict("slug 已存在");
        }
        News news = News.builder()
                .slug(form.slug().trim())
                .title(form.title())
                .category(form.category())
                .coverUrl(form.coverUrl())
                .summary(form.summary())
                .content(HtmlSanitizer.clean(form.content()))
                .authorId(authorId)
                .featured(Boolean.TRUE.equals(form.featured()) ? 1 : 0)
                .sortOrder(form.sortOrder() == null ? 0 : form.sortOrder())
                .status(form.status() == null ? "draft" : form.status())
                .viewCount(0)
                .build();
        if ("published".equals(news.getStatus())) {
            news.setPublishedAt(LocalDateTime.now());
        }
        newsMapper.insert(news);
        saveSeo(news.getId(), form.seo());
        cache.evictAfterCommit(CacheKeys.nullNews(news.getSlug()));
        return news;
    }

    @Transactional
    public void update(Long id, NewsForm form) {
        News news = getOrThrow(id);
        if (form.slug() != null && !form.slug().isBlank() && !form.slug().trim().equals(news.getSlug())) {
            String newSlug = form.slug().trim();
            // 与 create 对齐的唯一性校验，避免撞唯一索引落 500
            if (newsMapper.findBySlug(newSlug).isPresent()) {
                throw BusinessException.conflict("slug 已存在");
            }
            news.setSlug(newSlug);
        }
        if (form.title() != null) {
            news.setTitle(form.title());
        }
        if (form.category() != null) {
            news.setCategory(form.category());
        }
        if (form.coverUrl() != null) {
            news.setCoverUrl(form.coverUrl());
        }
        if (form.summary() != null) {
            news.setSummary(form.summary());
        }
        if (form.content() != null) {
            news.setContent(HtmlSanitizer.clean(form.content()));
        }
        if (form.featured() != null) {
            news.setFeatured(form.featured() ? 1 : 0);
        }
        if (form.sortOrder() != null) {
            news.setSortOrder(form.sortOrder());
        }
        if (form.status() != null && !form.status().equals(news.getStatus())) {
            changeStatus(news, form.status());
        }
        newsMapper.updateById(news);
        saveSeo(id, form.seo());
        cache.evictAfterCommit(CacheKeys.nullNews(news.getSlug()));
    }

    /** 局部列更新 + 投影读，理由同 {@link WorkAdminService#changeStatus}（B-12）。 */
    @Transactional
    public void changeStatus(Long id, String status) {
        News news = loadStatusSnapshot(id);
        changeStatus(news, status);
        newsMapper.update(null, new LambdaUpdateWrapper<News>()
                .set(News::getStatus, news.getStatus())
                .set(News::getPublishedAt, news.getPublishedAt())
                .set(News::getUpdatedAt, LocalDateTime.now())
                .eq(News::getId, id));
    }

    /** 只读 id/sortOrder、只写 sortOrder，理由同 {@link WorkAdminService#sort}（B-12）。 */
    @Transactional
    public void sort(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<News> all = newsMapper.selectList(new LambdaQueryWrapper<News>()
                .select(News::getId, News::getSortOrder));
        Map<Long, News> byId = new LinkedHashMap<>();
        Map<Long, Integer> oldOrders = new LinkedHashMap<>();
        for (News n : all) {
            byId.put(n.getId(), n);
            oldOrders.put(n.getId(), n.getSortOrder());
        }
        int order = 0;
        for (Long id : ids) {
            News n = byId.remove(id);
            if (n != null) {
                n.setSortOrder(order++);
            }
        }
        for (News n : byId.values()) {
            n.setSortOrder(order++);
        }
        // 仅更新 sortOrder 实际变化的行，避免全表无条件 UPDATE
        LocalDateTime now = LocalDateTime.now();
        for (News n : all) {
            if (!java.util.Objects.equals(oldOrders.get(n.getId()), n.getSortOrder())) {
                newsMapper.update(null, new LambdaUpdateWrapper<News>()
                        .set(News::getSortOrder, n.getSortOrder())
                        .set(News::getUpdatedAt, now)
                        .eq(News::getId, n.getId()));
            }
        }
    }

    /** 状态变更只需要 status/publishedAt，不必把 MEDIUMTEXT 正文读出来（B-12）。 */
    private News loadStatusSnapshot(Long id) {
        News news = newsMapper.selectOne(new LambdaQueryWrapper<News>()
                .select(News::getId, News::getStatus, News::getPublishedAt)
                .eq(News::getId, id));
        if (news == null) {
            throw BusinessException.notFound("新闻不存在");
        }
        return news;
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        commentMapper.deleteByTargetTypeAndTargetId("news", id);
        settingsMapper.findBySettingKey(seoKey(id)).ifPresent(s -> settingsMapper.deleteById(s.getId()));
        newsMapper.deleteById(id);
    }

    private void changeStatus(News news, String status) {
        if (!List.of("draft", "published", "offline").contains(status)) {
            throw BusinessException.badRequest("非法状态: " + status);
        }
        news.setStatus(status);
        if ("published".equals(status)) {
            news.setPublishedAt(LocalDateTime.now());
        }
    }

    private News getOrThrow(Long id) {
        return Optional.ofNullable(newsMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("新闻不存在"));
    }

    private void saveSeo(Long newsId, Map<String, String> seo) {
        if (seo == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(seo);
            SiteSettings setting = settingsMapper.findBySettingKey(seoKey(newsId))
                    .orElseGet(() -> SiteSettings.builder()
                            .groupName(SEO_GROUP)
                            .settingKey(seoKey(newsId))
                            .description("新闻 SEO：" + newsId)
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
    private Map<String, String> loadSeo(Long newsId) {
        return settingsMapper.findBySettingKey(seoKey(newsId))
                .map(s -> {
                    try {
                        return objectMapper.readValue(s.getSettingValue(), Map.class);
                    } catch (Exception e) {
                        return Map.of();
                    }
                })
                .orElse(Map.of());
    }

    private String seoKey(Long newsId) {
        return SEO_KEY_PREFIX + newsId;
    }

    public record NewsForm(String title, String slug, String category, String coverUrl,
                           String summary, String content, Boolean featured, Integer sortOrder,
                           String status, Map<String, String> seo) {
    }
}
