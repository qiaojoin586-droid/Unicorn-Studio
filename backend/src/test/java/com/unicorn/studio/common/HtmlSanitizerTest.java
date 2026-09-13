package com.unicorn.studio.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-23 的回归测试：正文长度上限、外链图白名单、以及基本的消毒行为。
 * <p>
 * 白名单那部分尤其值得测：{@code configureAllowedOrigins} 此前<b>全仓无调用者</b>，
 * 意味着 {@code app.upload.image-allow-origins} 填了也不生效、外链图恒被拒。
 * 现在接上了，就必须有一个测试确认「接上之后确实是放行的」——
 * 否则可能修完还是被拒，而表现只是「图片莫名其妙不显示」，很难定位。
 */
class HtmlSanitizerTest {

    @AfterEach
    void resetWhitelist() {
        // 静态状态：每个用例后复原，避免用例之间互相影响
        HtmlSanitizer.configureAllowedOrigins(null);
    }

    // ---------- 长度上限 ----------

    @Test
    @DisplayName("超长正文被拒绝（而不是静默截断）")
    void rejectsOversizedContent() {
        String tooLong = "a".repeat(HtmlSanitizer.MAX_CONTENT_LENGTH + 1);
        BusinessException e = assertThrows(BusinessException.class, () -> HtmlSanitizer.clean(tooLong));
        assertTrue(e.getMessage().contains("上限"), "实际报错：" + e.getMessage());
    }

    @Test
    @DisplayName("恰好等于上限的正文放行（边界值）")
    void acceptsContentAtLimit() {
        // 用纯文本构造：不会因为消毒被改写，也不需要真的解析 20 万个标签
        String atLimit = "a".repeat(HtmlSanitizer.MAX_CONTENT_LENGTH);
        assertDoesNotThrow(() -> HtmlSanitizer.clean(atLimit));
    }

    @Test
    @DisplayName("null / 空白输入原样返回，不做长度判断")
    void passesThroughBlank() {
        assertEquals(null, HtmlSanitizer.clean(null));
        assertEquals("", HtmlSanitizer.clean(""));
        assertEquals("   ", HtmlSanitizer.clean("   "));
    }

    // ---------- 外链图白名单（B-23③ 的修复点）----------

    @Test
    @DisplayName("未配置白名单时：本站 /uploads/ 放行，外链图被清除")
    void withoutWhitelistOnlyLocalUploadsAllowed() {
        String html = "<p><img src=\"/uploads/2026/09/a.jpg\"><img src=\"https://cdn.example.com/b.jpg\"></p>";
        String cleaned = HtmlSanitizer.clean(html);
        assertTrue(cleaned.contains("/uploads/2026/09/a.jpg"), "本站上传图应保留，实际：" + cleaned);
        assertFalse(cleaned.contains("cdn.example.com"), "未配白名单时外链图应被清除，实际：" + cleaned);
    }

    @Test
    @DisplayName("配置白名单后：白名单域的外链图放行，其余仍被清除")
    void whitelistedDomainAllowed() {
        HtmlSanitizer.configureAllowedOrigins("cdn.example.com, img.other.cn");
        String html = "<img src=\"https://cdn.example.com/b.jpg\">"
                + "<img src=\"https://img.other.cn/c.png\">"
                + "<img src=\"https://evil.example.net/d.jpg\">";
        String cleaned = HtmlSanitizer.clean(html);

        assertTrue(cleaned.contains("cdn.example.com"), "白名单域应放行，实际：" + cleaned);
        assertTrue(cleaned.contains("img.other.cn"), "第二个白名单域（含空格）也应放行，实际：" + cleaned);
        assertFalse(cleaned.contains("evil.example.net"), "非白名单域应被清除，实际：" + cleaned);
    }

    @Test
    @DisplayName("重新配置白名单会整体替换（而不是累加旧值）")
    void reconfiguringReplacesWhitelist() {
        HtmlSanitizer.configureAllowedOrigins("old.example.com");
        HtmlSanitizer.configureAllowedOrigins("new.example.com");

        String cleaned = HtmlSanitizer.clean(
                "<img src=\"https://old.example.com/a.jpg\"><img src=\"https://new.example.com/b.jpg\">");
        assertFalse(cleaned.contains("old.example.com"), "旧白名单应已失效，实际：" + cleaned);
        assertTrue(cleaned.contains("new.example.com"), "新白名单应生效，实际：" + cleaned);
    }

    // ---------- 基本消毒行为（确认改造没有破坏原有能力）----------

    @Test
    @DisplayName("script 标签与事件属性仍被清除")
    void stillStripsScripts() {
        String cleaned = HtmlSanitizer.clean(
                "<p onclick=\"evil()\">正文</p><script>alert(1)</script><a href=\"javascript:evil()\">x</a>");
        assertFalse(cleaned.contains("<script"), "script 标签应被清除，实际：" + cleaned);
        assertFalse(cleaned.contains("onclick"), "事件属性应被清除，实际：" + cleaned);
        assertFalse(cleaned.toLowerCase().contains("javascript:"), "javascript: 协议应被清除，实际：" + cleaned);
        assertTrue(cleaned.contains("正文"), "正常文本应保留");
    }

    @Test
    @DisplayName("常用排版标签保留")
    void keepsFormattingTags() {
        String cleaned = HtmlSanitizer.clean("<h2>标题</h2><p>段落<strong>加粗</strong></p><ul><li>项</li></ul>");
        assertTrue(cleaned.contains("<h2>"), "实际：" + cleaned);
        assertTrue(cleaned.contains("<strong>"), "实际：" + cleaned);
        assertTrue(cleaned.contains("<li>"), "实际：" + cleaned);
    }
}
