package com.unicorn.studio.AIWorker.config;

import com.unicorn.studio.common.BusinessException;

import java.net.URI;
import java.util.Map;
import java.util.Arrays;

/** 仅用于助手消息的外观配置，与模型参数、用户头像和站点品牌图标独立。 */
public record AiMessageAvatar(String mode, String url) {

    public static final String KEY_MODE = "ai.message_avatar_mode";
    public static final String KEY_URL = "ai.message_avatar_url";
    public static final String CACHE_KEY = "cache:ai:message-avatar";

    public static AiMessageAvatar from(Map<String, String> values) {
        String mode = "custom".equals(values.get(KEY_MODE)) ? "custom" : "default";
        String url = values.get(KEY_URL);
        url = url == null ? "" : url.trim();
        return new AiMessageAvatar(mode, isSafeUrl(url) ? url : "");
    }

    public String effectiveUrl() {
        return "custom".equals(mode) && url != null ? url : "";
    }

    public static String validateUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (!isSafeUrl(url)) {
            throw BusinessException.badRequest("头像地址须为已上传图片的地址或有效的 HTTP/HTTPS 地址");
        }
        return url;
    }

    private static boolean isSafeUrl(String value) {
        if (value.isEmpty()) return true;
        if (value.length() > 2048 || value.matches("(?s).*[\\x00-\\x20\\x7f\\\\].*")) return false;
        try {
            URI uri = URI.create(value);
            if (value.startsWith("/uploads/")) {
                String decoded = uri.getPath().replace('\\', '/');
                return uri.getRawAuthority() == null && decoded.startsWith("/uploads/")
                        && !Arrays.asList(decoded.split("/")).contains("..");
            }
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
