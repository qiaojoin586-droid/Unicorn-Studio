package com.unicorn.studio.AIWorker.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.service.LearningService;
import com.unicorn.studio.vo.LearningCardVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学习资料列表（工具层建议 §4 P0-5）。
 * <p>
 * <b>这是 P0 里价值最高的一个</b>：{@code LearningService.findAll()} 已经带 30 分钟 Redis 缓存，
 * 工具调用几乎零成本。
 */
@Component
public class LearningListTool implements AiTool {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 10;

    private final LearningService learningService;
    private final ObjectMapper mapper;

    public LearningListTool(LearningService learningService, ObjectMapper mapper) {
        this.learningService = learningService;
        this.mapper = mapper;
    }

    @Override
    public AiToolDefinition definition() {
        return AiToolDefinition.withParams("learning_list",
                "列出/筛选工作室的学习资料（标题、分类、标签、简介、是否需要附件）。"
                        + "当用户问「有没有 XX 的学习资料」「教程」「文档」「资源推荐」时优先调用本工具。",
                List.of(
                        // 输入参数的规格说明，每个参数占一行
                        AiToolDefinition.Param.optional("category", AiToolDefinition.ParamType.STRING,
                                "资料分类，如「前端」「后端」"),
                        AiToolDefinition.Param.optional("keyword", AiToolDefinition.ParamType.STRING,
                                "在资料标题/简介/标签里做关键词匹配"),
                        AiToolDefinition.Param.optional("limit", AiToolDefinition.ParamType.INTEGER,
                                "返回条数，默认 8，上限 10")));
    }

    @Override
    public String invoke(String argumentsJson) {
        Map<String, String> args = AiToolSupport.parseArgs(argumentsJson, mapper);
        // 获取类别参数
        String category = AiToolSupport.stringArg(args, "category");
        // 获取关键词参数
        String keyword = AiToolSupport.stringArg(args, "keyword");
        // 获取返回条数限制参数
        int limit = AiToolSupport.intArg(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);

        // ⚠️ 一次性取回全部（带 30min 缓存），过滤在内存里做
        // 把资料列表全部取回，在内存里过滤，这样可以避免频繁数据库查询
        List<LearningCardVO> all = learningService.findAll();
        List<Map<String, Object>> items = new ArrayList<>();
        int matched = 0;
        for (LearningCardVO c : all) {
            if (c == null) {
                continue;
            }
            if (category != null && !category.equals(c.category())) {
                continue;
            }
            // ⚠️ keyword 刻意【不下推 SQL】：后台搜索用的是 like %x%，
            // 而在公开入口上做全表 like 是性能陷阱（§3.4 site_search 的否决理由）。
            // 缓存结果集只有几十条，内存过滤既安全又够用（§4 P0-5 第 ② 条）
            //matches表示关键词是否匹配，即关键词是否在标题/简介/标签里
            if (keyword != null && !matches(c, keyword)) {
                continue;
            }
            // 统计匹配的资料数
            matched++;
            // ⚠️ continue 不能 break：break 会让后面的不再计数，total 就变成"本页条数"了
            if (items.size() >= limit) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.id()); // 资料 ID
            item.put("title", c.title()); // 资料标题
            item.put("category", c.category()); // 资料分类
            item.put("tags", c.tags()); // 资料标签
            item.put("description", AiToolSupport.truncate(c.description(), 80)); // 资料简介,截断80个字符
            // ⚠️ 只给"有没有附件"，不给附件路径：/uploads/ 下的文件可能未审核，
            // 路径进了模型上下文就等于让模型吐出一个可下载链接（§4 P0-5 第 ③ 条）
            item.put("hasAttachment", c.attachmentUrl() != null && !c.attachmentUrl().isBlank()); // 资料是否有附件
            // ⚠️ url 是【站内详情页路径】不是附件路径，给了模型能说"到学习资料页看这篇"。
            // ⚠️ 但空值不能放进去：模型看到 "url": null 会当成"这个链接坏了/不存在"，
            // 反而误导它答"这份资料的链接有问题"。所以只在有值时才放这个 key。
            if (c.url() != null && !c.url().isBlank()) {
                item.put("url", c.url());
            }
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        // 匹配的资料总数，不是本页条数，本页有数量限制
        data.put("total", matched);
        data.put("items", items);
        return AiToolSupport.ok(mapper, data);
    }

    /** 关键词在标题/简介/标签里任一命中即可。⚠️ 不区分大小写：模型可能传「Vue」而库里是「vue」。 */
    private boolean matches(LearningCardVO c, String keyword) {
        String kw = keyword.toLowerCase(Locale.ROOT); // 转换为小写
        return contains(c.title(), kw) || contains(c.description(), kw) || contains(c.tags(), kw);
    }

    private boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword); // 判断 text 是否包含 lowerKeyword，模糊匹配
    }
}
