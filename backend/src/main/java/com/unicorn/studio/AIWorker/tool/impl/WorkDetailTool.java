package com.unicorn.studio.AIWorker.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.common.SlugValidator;
import com.unicorn.studio.model.Work;
import com.unicorn.studio.service.WorkService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 作品详情（工具层建议 §4 P0-3）。
 */
@Component
public class WorkDetailTool implements AiTool {

    private static final int MAX_CONTENT = 1500;

    private final WorkService workService;
    private final ObjectMapper mapper;

    public WorkDetailTool(WorkService workService, ObjectMapper mapper) {
        this.workService = workService;
        this.mapper = mapper;
    }

    @Override
    public AiToolDefinition definition() {
        return AiToolDefinition.withParams("work_detail",
                "按 slug 获取某个作品的详情（正文与外链）。"
                        + "必须先通过 work_list 或用户明确给出的 slug 得到 slug；"
                        + "若用户只给了作品名而你不知道 slug，先调 work_list。",
                List.of(AiToolDefinition.Param.of("slug", AiToolDefinition.ParamType.STRING,
                        "作品的 slug，形如 unicorn-portal（必填）")));
    }

    @Override
    public String invoke(String argumentsJson) {
        Map<String, String> args = AiToolSupport.parseArgs(argumentsJson, mapper);
        String slug = AiToolSupport.stringArg(args, "slug");
        if (slug == null) {
            return AiToolSupport.error(mapper, "invalid_argument", "缺少 slug 参数", "slug", false);
        }
        // ⚠️ 先形状校验再查库（§4 P0-3 第 ① 条）：模型编的 slug 大概率不合规，
        /** 不先挡掉就是一次无效查询
         * 检验slug是否合规（slug是url最后的一部分，形如 /works/unicorn-portal，规则是只含小写字母、数字和连字符）
         * 用来代替id让url更友好，更利于SEO（搜索引擎优化，让网站更容易被搜索引擎收录）
         */
        if (!SlugValidator.isValidSlug(slug)) {
            return AiToolSupport.error(mapper, "invalid_argument",
                    "slug 形如 unicorn-portal（只含小写字母、数字和连字符）", "slug", false);
        }
        Optional<Work> found = workService.findPublishedBySlug(slug);
        if (found.isEmpty()) {
            // ⚠️ found:false 明确告诉模型"确实没有"，防止它拿到空结果自己编一个作品
            // （这是最常见的幻觉形态，§4 P0-3 第 ④ 条）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", false);
            data.put("reason", "not_found");
            return AiToolSupport.ok(mapper, data);
        }
        Work w = found.get();
        String plain = AiToolSupport.toPlainText(w.getContent());
        boolean truncated = plain.length() > MAX_CONTENT;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("found", true);
        data.put("slug", w.getSlug());
        data.put("title", w.getTitle());
        data.put("summary", w.getSummary());
        data.put("content", AiToolSupport.truncate(plain, MAX_CONTENT));
        data.put("truncated", truncated);
        if (w.getExternalLink() != null && !w.getExternalLink().isBlank()) {
            data.put("externalLink", w.getExternalLink());
        }
        // ⚠️ 刻意不返回 clientName（客户名）：可能涉及保密，且访客问"你们做过什么"用不上。
        // 也不返回正文里的原始 HTML：模型只吃纯文本（AiToolSupport.toPlainText 已转好）
        return AiToolSupport.ok(mapper, data);
    }
}
