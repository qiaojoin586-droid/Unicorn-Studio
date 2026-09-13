package com.unicorn.studio.vo;

import java.util.List;

/**
 * 「加入我们」页面内容 VO（V17）：整页文案/二维码改为后台可维护。
 *
 * @param eyebrow   小标题（页面顶部眉标，可空则前台不渲染）
 * @param title     主标题
 * @param subtitle  副标题（高亮一句）
 * @param paragraphs 正文段落列表（后台按多行文本维护，按空行切分）
 * @param qrImage   社群二维码图片地址
 * @param qrCaption 二维码下方说明文字
 */
public record JoinPageVO(
        String eyebrow,
        String title,
        String subtitle,
        List<String> paragraphs,
        String qrImage,
        String qrCaption) {
}
