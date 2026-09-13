package com.unicorn.studio.common.cache;

import com.unicorn.studio.controller.PageBackgroundController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-03 的回归测试：页面背景的「白名单」与「缓存失效键」必须来自同一份定义。
 * <p>
 * <b>为什么值得单独锁住这个不变量</b>：原缺陷不是「少写了一个 key」，而是<b>两份列表各自演化</b>——
 * {@code PageBackgroundController} 的白名单加了 {@code works}，而 {@code CarouselAdminService}
 * 的失效点还写着两个页面键。结果是 {@code cache:bg:works} 只被启动预热写入、永不被清除，
 * 管理员改完「作品页」背景后前台最长 30 分钟不生效，且没有任何强制刷新手段。
 * <p>
 * 这类缺陷的两个特点决定了必须用测试兜住：① 编译期完全无声；② 表现是「后台改了前台没变」，
 * 排查时第一嫌疑通常在缓存逻辑本身，而不是「少清了一个 key」。
 */
class CacheKeysBackgroundTest {

    @Test
    @DisplayName("ALL_BG_KEYS 覆盖全部页面，且与白名单同源")
    void allBgKeysCoversEverySupportedPage() {
        List<String> pages = CacheKeys.BG_PAGE_KEYS;
        List<String> keys = CacheKeys.ALL_BG_KEYS;

        assertEquals(pages.size(), keys.size(),
                "每个页面都应有一个对应的缓存键；数量不符说明两者已漂移");
        for (String page : pages) {
            assertTrue(keys.contains(CacheKeys.bg(page)),
                    "页面 " + page + " 的缓存键不在 ALL_BG_KEYS 中 —— 失效时会漏掉它（B-03 复发）");
        }
    }

    @Test
    @DisplayName("前台白名单与 BG_PAGE_KEYS 完全一致（防止有人在 Controller 里另写一份）")
    void controllerWhitelistMatchesPageKeys() throws Exception {
        Field field = PageBackgroundController.class.getDeclaredField("SUPPORTED_PAGES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> supported = (Set<String>) field.get(null);

        assertEquals(Set.copyOf(CacheKeys.BG_PAGE_KEYS), supported,
                "Controller 的白名单必须来自 CacheKeys.BG_PAGE_KEYS；"
                        + "若在此另写一份，新增页面时又会漏配失效点");
    }

    @Test
    @DisplayName("缓存键形如 cache:bg:{page}，无重复")
    void keysAreWellFormedAndUnique() {
        List<String> keys = CacheKeys.ALL_BG_KEYS;
        assertEquals(keys.size(), Set.copyOf(keys).size(), "ALL_BG_KEYS 不应有重复项");
        for (String key : keys) {
            assertTrue(key.startsWith(CacheKeys.BG_PREFIX),
                    "缓存键 " + key + " 未使用统一前缀 " + CacheKeys.BG_PREFIX);
        }
    }
}
