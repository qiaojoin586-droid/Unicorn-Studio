package com.unicorn.studio.vo;

import java.util.List;

/**
 * 页脚（Footer）配置（V13）：整块页脚文案、链接与社交图标改为后台可维护。
 * <p>
 * 与「关于我们」（V12）同属单例页面配置，复用 site_settings 存储与写入通道，不新建表。
 * 字段与后台「页脚设置」表单一一对应。
 */
public record FooterVO(
        String brandName,
        String brandIntro,
        boolean showParticles,
        String linksTitle,
        List<Link> links,
        String socialTitle,
        List<Social> socials,
        String email,
        String emailLabel,
        String copyrightLeft,
        String copyrightRight
) {

    /**
     * 快速链接项。
     * <p>
     * {@code url} 支持站内路径（{@code /works}）、站内锚点（{@code #top}）与外部链接（{@code https://...}）；
     * {@code newTab} 为真时前台以 {@code target="_blank"} + {@code rel="noopener noreferrer"} 打开。
     */
    public record Link(String label, String url, boolean newTab) {
    }

    /**
     * 社交图标项。
     * <p>
     * {@code icon} 为内置图标库的 key（见 {@code admin/src/constants/socialIcons.ts} 与
     * {@code frontend/src/constants/socialIcons.js}）；{@code iconUrl} 为自定义图标地址，
     * 非空时优先于 {@code icon}；{@code title} 同时用作悬停提示与无障碍 label。
     */
    public record Social(String icon, String iconUrl, String url, String title) {
    }
}
