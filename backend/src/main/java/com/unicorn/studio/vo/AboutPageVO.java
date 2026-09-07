package com.unicorn.studio.vo;

import java.util.List;

/**
 * 「关于我们」页面内容 VO（V12）：整页文案由后台「关于我们」页面维护，前台只读。
 * <p>
 * 存储落在 site_settings 的 about 组（单例页面配置，复用既有设置存储与写入通道）。
 * content 为纯文本，段落之间以空行（\n\n）分隔，前台按段落渲染。
 */
public record AboutPageVO(
        String eyebrow,
        String title,
        String subtitle,
        String content,
        String imageUrl,
        List<Stat> stats) {

    /** 统计指标：值 + 标签，如 50+ / 完成项目。 */
    public record Stat(String value, String label) {
    }
}
