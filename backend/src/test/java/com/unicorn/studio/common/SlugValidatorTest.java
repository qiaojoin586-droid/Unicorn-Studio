package com.unicorn.studio.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SlugValidator 形状校验单测（Redis 方案 §4.1 方案 P2，穿透防护）。
 * 核心断言：在打缓存/打库之前拒绝掉不可能存在的参数形状。
 */
class SlugValidatorTest {

    @Test
    void validSlug_accepted() {
        assertTrue(SlugValidator.isValidSlug("a"));
        assertTrue(SlugValidator.isValidSlug("hello-world"));
        assertTrue(SlugValidator.isValidSlug("abc-123-def"));
        assertTrue(SlugValidator.isValidSlug("0"));
        assertTrue(SlugValidator.isValidSlug("a" + "x".repeat(99))); // 恰好 100 字符（上限）
    }

    @Test
    void invalidSlug_rejected() {
        assertFalse(SlugValidator.isValidSlug(null));
        assertFalse(SlugValidator.isValidSlug(""));
        assertFalse(SlugValidator.isValidSlug("UPPER"));
        assertFalse(SlugValidator.isValidSlug("has space"));
        assertFalse(SlugValidator.isValidSlug("under_score"));
        assertFalse(SlugValidator.isValidSlug("-leading"));
        assertFalse(SlugValidator.isValidSlug("a/b"));
        assertFalse(SlugValidator.isValidSlug("中文"));
        assertFalse(SlugValidator.isValidSlug("..%2f"));
        assertFalse(SlugValidator.isValidSlug("a.php"));
        assertFalse(SlugValidator.isValidSlug("a" + "x".repeat(100))); // 101 字符超长
    }

    @Test
    void positiveId() {
        assertTrue(SlugValidator.isPositiveId(1L));
        assertTrue(SlugValidator.isPositiveId(9999L));
        assertFalse(SlugValidator.isPositiveId(null));
        assertFalse(SlugValidator.isPositiveId(0L));
        assertFalse(SlugValidator.isPositiveId(-1L));
    }
}
