package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.HtmlSanitizer;
import com.unicorn.studio.common.PageResult;
import com.unicorn.studio.model.News;
import com.unicorn.studio.model.SiteSettings;
import com.unicorn.studio.repository.CommentRepository;
import com.unicorn.studio.repository.NewsRepository;
import com.unicorn.studio.repository.SiteSettingsRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 新闻管理（模式同作品，authorId 默认当前管理员）。 */
@Service
public class NewsAdminService {

    private static final String SEO_GROUP = "seo";
    private static final String SEO_KEY_PREFIX = "seo.news.";

    private final NewsRepository newsRepository;
    private final CommentRepository commentRepository;
    private final SiteSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NewsAdminService(NewsRepository newsRepository,
                            CommentRepository commentRepository,
                            SiteSettingsRepository settingsRepository) {
        this.newsRepository = newsRepository;
        this.commentRepository = commentRepository;
        this.settingsRepository = settingsRepository;
    }

    public PageResult<News> list(String keyword, String category, String status, int page, int size) {
        Specification<News> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("title"), like),
                        cb.like(root.get("slug"), like)));
            }
            if (category != null && !category.isBlank()) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<News> news = newsRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "sortOrder", "id")));
        return PageResult.of(news);
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
        if (newsRepository.findBySlug(form.slug().trim()).isPresent()) {
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
        news = newsRepository.save(news);
        saveSeo(news.getId(), form.seo());
        return news;
    }

    @Transactional
    public void update(Long id, NewsForm form) {
        News news = getOrThrow(id);
        if (form.slug() != null && !form.slug().isBlank() && !form.slug().trim().equals(news.getSlug())) {
            news.setSlug(form.slug().trim());
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
        newsRepository.save(news);
        saveSeo(id, form.seo());
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        News news = getOrThrow(id);
        changeStatus(news, status);
        newsRepository.save(news);
    }

    @Transactional
    public void sort(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<News> all = newsRepository.findAll();
        Map<Long, News> byId = new LinkedHashMap<>();
        for (News n : all) {
            byId.put(n.getId(), n);
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
        newsRepository.saveAll(all);
    }

    @Transactional
    public void delete(Long id) {
        getOrThrow(id);
        commentRepository.deleteByTargetTypeAndTargetId("news", id);
        settingsRepository.findBySettingKey(seoKey(id)).ifPresent(settingsRepository::delete);
        newsRepository.deleteById(id);
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
        return newsRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("新闻不存在"));
    }

    private void saveSeo(Long newsId, Map<String, String> seo) {
        if (seo == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(seo);
            SiteSettings setting = settingsRepository.findBySettingKey(seoKey(newsId))
                    .orElseGet(() -> SiteSettings.builder()
                            .groupName(SEO_GROUP)
                            .settingKey(seoKey(newsId))
                            .description("新闻 SEO：" + newsId)
                            .build());
            setting.setSettingValue(json);
            settingsRepository.save(setting);
        } catch (Exception e) {
            throw BusinessException.badRequest("SEO 数据格式不正确");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadSeo(Long newsId) {
        return settingsRepository.findBySettingKey(seoKey(newsId))
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
