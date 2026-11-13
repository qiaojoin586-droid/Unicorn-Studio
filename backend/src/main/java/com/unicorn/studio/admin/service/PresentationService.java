package com.unicorn.studio.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.common.cache.CacheObjectMapper;
import com.unicorn.studio.vo.PresentationVO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.net.*;
import java.util.*;

/** Uses the existing settings snapshot and after-commit invalidation; no second page cache. */
@Service
public class PresentationService {
    public static final String GROUP = "content";
    public static final String KEY_PREFIX = "content.presentation.";
    private static final Logger log = LoggerFactory.getLogger(PresentationService.class);
    private static final ObjectMapper JSON = CacheObjectMapper.create().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Set<String> URL_FIELDS = Set.of("primaryUrl", "secondaryUrl", "invitationUrl", "noteLinkUrl", "joinUrl");
    private final SiteSettingsService settings;
    private final Validator validator;

    public PresentationService(SiteSettingsService settings, Validator validator) {
        this.settings = settings;
        this.validator = validator;
    }
    public PresentationVO load() {
        Map<String,String> values = settings.listGroup(GROUP);
        return new PresentationVO(read("home", PresentationVO.Home.class, values), read("about", PresentationVO.About.class, values),
                read("footer", PresentationVO.Footer.class, values), read("join", PresentationVO.Join.class, values));
    }
    public PresentationVO.Home loadHome() { return read("home", PresentationVO.Home.class, settings.listGroup(GROUP)); }
    public PresentationVO.About loadAbout() { return read("about", PresentationVO.About.class, settings.listGroup(GROUP)); }
    public PresentationVO.Footer loadFooter() { return read("footer", PresentationVO.Footer.class, settings.listGroup(GROUP)); }
    public PresentationVO.Join loadJoin() { return read("join", PresentationVO.Join.class, settings.listGroup(GROUP)); }
    public PresentationVO.Home saveHome(PresentationVO.Home form) { return save("home", form, PresentationVO.Home.class); }
    public PresentationVO.About saveAbout(PresentationVO.About form) { return save("about", form, PresentationVO.About.class); }
    public PresentationVO.Footer saveFooter(PresentationVO.Footer form) { return save("footer", form, PresentationVO.Footer.class); }
    public PresentationVO.Join saveJoin(PresentationVO.Join form) { return save("join", form, PresentationVO.Join.class); }

    private <T> T read(String section, Class<T> type, Map<String,String> values) {
        ObjectNode base = emptySection(type);
        String stored = values.get(KEY_PREFIX + section);
        if (stored == null || stored.isBlank()) return convert(base, type);
        try {
            JsonNode patch = JSON.readTree(stored);
            if (patch == null || !patch.isObject()) throw new IllegalArgumentException("Expected a section object");
            T value = convert(mergeKnown(base, patch), type);
            validate(value);
            return value;
        } catch (JsonProcessingException | IllegalArgumentException | BusinessException e) {
            log.warn("页面展示配置格式无效，返回空内容，请在管理端重新保存: section={}", section);
            return convert(emptySection(type), type);
        }
    }
    private <T> T save(String section, T form, Class<T> type) {
        if (form == null) throw BusinessException.badRequest("页面展示配置不能为空");
        // Omitted/null optional fields retain their previously saved values; explicit empty/false replaces them.
        T current = read(section, type, settings.listGroup(GROUP));
        T value = convert(mergeKnown((ObjectNode) JSON.valueToTree(current), JSON.valueToTree(form)), type);
        validate(value);
        try {
            settings.updateAll(List.of(new SiteSettingsService.KeyValueRequest(GROUP, KEY_PREFIX + section, JSON.writeValueAsString(value))));
        } catch (JsonProcessingException e) { throw BusinessException.badRequest("页面展示配置格式不正确"); }
        return value;
    }
    /** Keep the public field contract stable without inventing content when settings are absent. */
    private ObjectNode emptySection(Class<?> type) {
        ObjectNode fields = JSON.createObjectNode();
        for (var field : type.getRecordComponents()) {
            if (field.getType() == String.class) fields.put(field.getName(), "");
            else if (List.class.isAssignableFrom(field.getType())) fields.putArray(field.getName());
            else if (field.getType() == Boolean.class) fields.put(field.getName(), false);
            else fields.putNull(field.getName());
        }
        return fields;
    }
    /** Iterate known contract properties only. Arrays replace as a unit, including an explicit []. */
    private ObjectNode mergeKnown(ObjectNode base, JsonNode patch) {
        base.fieldNames().forEachRemaining(name -> {
            JsonNode value = patch.get(name);
            if (value != null && !value.isNull()) {
                if (base.get(name).isObject() && value.isObject()) base.set(name, mergeKnown((ObjectNode) base.get(name), value));
                else base.set(name, value.deepCopy());
            }
        });
        return base;
    }
    private <T> T convert(JsonNode value, Class<T> type) {
        try { return JSON.treeToValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid presentation section", e); }
    }
    private void validate(Object value) {
        if (value == null) throw BusinessException.badRequest("页面展示配置不能为空");
        Set<ConstraintViolation<Object>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            ConstraintViolation<Object> first = violations.stream().min(Comparator.comparing(v -> v.getPropertyPath().toString())).orElseThrow();
            throw BusinessException.badRequest(first.getPropertyPath() + ": " + first.getMessage());
        }
        JsonNode fields = JSON.valueToTree(value);
        for (String name : URL_FIELDS) {
            JsonNode url = fields.get(name);
            if (url != null && !url.isNull() && !isSafeUrl(url.asText()))
                throw BusinessException.badRequest(name + ": 仅支持站内路径、锚点或 http/https 链接");
        }
        if (value instanceof PresentationVO.Home home) {
            if (Set.of("work", "learning").contains(home.recommendationType()) && home.recommendationId().isEmpty()) {
                throw BusinessException.badRequest("请选择要推荐的作品或学习资料");
            }
            validateBackground(home.backgroundMode(), home.backgroundImageUrl(), home.backgroundVideoUrl());
        } else if (value instanceof PresentationVO.About about) {
            validateBackground(about.backgroundMode(), about.backgroundImageUrl(), about.backgroundVideoUrl());
        }
    }
    private void validateBackground(String mode, String image, String video) {
        if (image.startsWith("#") || video.startsWith("#") || !isSafeUrl(image) || !isSafeUrl(video)) {
            throw BusinessException.badRequest("背景图片和视频仅支持站内路径或 http/https 链接");
        }
        if (!mode.isEmpty() && image.isEmpty()) {
            throw BusinessException.badRequest("请上传背景图片或视频封面");
        }
        if ("video".equals(mode) && video.isEmpty()) {
            throw BusinessException.badRequest("请上传背景视频");
        }
    }
    static boolean isSafeUrl(String value) {
        if (value == null || value.isEmpty()) return true;
        if (value.length() > 2048 || value.indexOf('\\') >= 0 || value.chars().anyMatch(c ->
                Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c))) return false;
        try {
            URI uri = new URI(value);
            if (value.startsWith("#")) return !uri.isAbsolute() && uri.getRawAuthority() == null;
            if (value.startsWith("/")) return !value.startsWith("//") && !uri.isAbsolute() && uri.getRawAuthority() == null;
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (URISyntaxException e) { return false; }
    }
}
