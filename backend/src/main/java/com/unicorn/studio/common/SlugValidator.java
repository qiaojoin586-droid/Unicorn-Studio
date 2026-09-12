package com.unicorn.studio.common;

import java.util.regex.Pattern;

/**
 * 前台标识符形状校验（Redis 方案 §4.1 方案 P2，穿透防护）。
 * <p>
 * 在打缓存、打库**之前**拒绝掉不可能存在的参数，挡掉绝大多数枚举/扫描流量
 * （`.php`、`..%2f`、超长随机串、负数 id 等），零一致性负担。
 */
public final class SlugValidator {

    /** slug 形状：小写字母/数字开头，后续可含字母/数字/连字符，长度 ≤ 100。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,99}$");

    private SlugValidator() {
    }

    public static boolean isValidSlug(String slug) {
        return slug != null && SLUG_PATTERN.matcher(slug).matches();
    }

    public static boolean isPositiveId(Long id) {
        return id != null && id > 0;
    }
}
