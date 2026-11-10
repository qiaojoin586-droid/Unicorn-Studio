package com.unicorn.studio.AIWorker.tool.impl;

import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.common.SlugValidator;
import com.unicorn.studio.service.WorkService;
import com.unicorn.studio.vo.WorkDetailVO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 作品详情（工具层建议 §4 P0-3）。
 */
@Component
public class WorkDetailTool implements AiTool {

    private static final int MAX_CONTENT = 1500;

    private final WorkService workService;

    public WorkDetailTool(WorkService workService) {
        this.workService = workService;
    }

    @Tool(name = "work_detail", description = "按 slug 获取某个作品的详情（正文与外链）。"
            + "必须先通过 work_list 或用户明确给出的 slug 得到 slug；"
            + "若用户只给了作品名而你不知道 slug，先调 work_list。")
    public Map<String, Object> workDetail(
            @ToolParam(description = "作品的 slug，形如 unicorn-portal（必填）") String slug) {
        slug = AiToolSupport.textArg(slug);
        if (slug == null) {
            return AiToolSupport.error("invalid_argument", "缺少 slug 参数", "slug", false);
        }
        // ⚠️ 先形状校验再查库（§4 P0-3 第 ① 条）：模型编的 slug 大概率不合规，
        /** 不先挡掉就是一次无效查询
         * 检验slug是否合规（slug是url最后的一部分，形如 /works/unicorn-portal，规则是只含小写字母、数字和连字符）
         * 用来代替id让url更友好，更利于SEO（搜索引擎优化，让网站更容易被搜索引擎收录）
         */
        if (!SlugValidator.isValidSlug(slug)) {
            return AiToolSupport.error("invalid_argument",
                    "slug 形如 unicorn-portal（只含小写字母、数字和连字符）", "slug", false);
        }
        // ⚠️ 现在返回详情 VO（正文在里面）而不是实体
        Optional<WorkDetailVO> found = workService.findPublishedBySlug(slug);
        if (found.isEmpty()) {
            // ⚠️ found:false 明确告诉模型"确实没有"，防止它拿到空结果自己编一个作品
            // （这是最常见的幻觉形态，§4 P0-3 第 ④ 条）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", false);
            data.put("reason", "not_found");
            return AiToolSupport.ok(data);
        }
        WorkDetailVO w = found.get();
        String plain = AiToolSupport.toPlainText(w.content());
        boolean truncated = plain.length() > MAX_CONTENT;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("slug", w.slug());
        data.put("title", w.title());
        data.put("summary", w.summary());
        data.put("content", AiToolSupport.truncate(plain, MAX_CONTENT));
        data.put("truncated", truncated);
        if (w.externalLink() != null && !w.externalLink().isBlank()) {
            data.put("externalLink", w.externalLink());
        }
        // ⚠️ 刻意不返回 clientName（客户名）：可能涉及保密，且访客问"你们做过什么"用不上。
        // 也不返回正文里的原始 HTML：模型只吃纯文本（AiToolSupport.toPlainText 已转好）
        return AiToolSupport.ok(data);
    }
}
