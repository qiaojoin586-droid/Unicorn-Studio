package com.unicorn.studio.AIWorker.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.AiToolDefinition;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.AIWorker.tool.support.SiteUrls;
import com.unicorn.studio.service.WorkService;
import com.unicorn.studio.vo.WorkCardVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发布作品列表（工具层建议 §4 P0-2）。
 */
@Component
public class WorkListTool implements AiTool {

    private static final int DEFAULT_LIMIT = 6;
    private static final int MAX_LIMIT = 10;

    private final WorkService workService;
    private final ObjectMapper mapper;
    private final SiteUrls siteUrls;

    public WorkListTool(WorkService workService, ObjectMapper mapper, SiteUrls siteUrls) {
        this.workService = workService;
        this.mapper = mapper;
        this.siteUrls = siteUrls;
    }

    @Override
    public AiToolDefinition definition() {
        return AiToolDefinition.withParams("work_list",
                "列出工作室的已发布作品卡片（标题、slug、分类、摘要、作者、日期）。"
                        + "当用户问「你们都做过什么项目」「有没有 XX 类的案例」「作品列表」时调用。"
                        + "只返回卡片摘要，不含正文；要看正文请再用 work_detail。",
                List.of(
                        AiToolDefinition.Param.optional("category", AiToolDefinition.ParamType.STRING,
                                "作品分类，如「竞赛」「论文」"),
                        AiToolDefinition.Param.optional("featured", AiToolDefinition.ParamType.STRING,
                                "只看精选时传 \"true\"，否则不传"),
                        AiToolDefinition.Param.optional("limit", AiToolDefinition.ParamType.INTEGER,
                                "返回条数，默认 6，上限 10")));
    }

    @Override
    public String invoke(String argumentsJson) {
        Map<String, String> args = AiToolSupport.parseArgs(argumentsJson, mapper);
        String category = AiToolSupport.stringArg(args, "category");
        boolean featuredOnly = "true".equalsIgnoreCase(AiToolSupport.stringArg(args, "featured"));
        int limit = AiToolSupport.intArg(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);

        // 获取已发布的作品列表。
        // ⚠️ WorkService.findPublished 现在返回【卡片 VO】而不是实体（学长把实体收敛到 VO 层了），
        // 这样正文（MEDIUMTEXT）不会随列表查询整列进内存。
        // ⚠️ Service 里 featured 优先、category 会被忽略 ⇒ 两个条件都传时以 featured 为准，
        //    所以这里按「有没有 featured」二选一，不把两个都塞进去（否则分类条件会被悄悄丢掉）。
        List<WorkCardVO> source = featuredOnly
                ? workService.findPublished(null, 1)
                : workService.findPublished(category, null);
        List<Map<String, Object>> items = new ArrayList<>();
        int matched = 0;
        for (WorkCardVO card : source) {
            matched++;
            // ⚠️ 这里必须 continue 不能 break：break 会让后面的作品不再参与计数，
            // total 就变成了"本页条数"而不是"匹配到的总数"（模型会答错"一共有多少个"）
            if (items.size() >= limit) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            // ⚠️ 返回 slug 不返回 id：前台作品详情路由是 /works/:slug，
            // slug 可直接用于下一步 work_detail 或给出可点击链接
            item.put("slug", card.slug());
            // ⚠️ slug 是【标识符】（下一步 work_detail(slug) 要用），不是地址；
            //   访客要的是能点的地址 ⇒ 额外给完整地址（前端详情路由是 /works/:slug）。
            //   两个都留：slug 给模型继续调用，url 给模型写进回答让访客点开。
            item.put("url", siteUrls.absolute("/works/" + card.slug()));
            item.put("title", card.title());
            // ⚠️ 卡片 VO 刻意不暴露 category（前台不展示它）⇒ 这里给 tagType 而不是 category：
            //   tagType 是 WorkTags.resolve 兜底后的徽章（award/paper/project/other），
            //   与真实分类（竞赛/论文）不是一回事 ⇒ 换个字段名，别让模型当成分类去回答。
            //   真实分类在 work_detail 里才有（WorkDetailVO 带 category）。
            item.put("tagType", card.tagType());
            item.put("summary", AiToolSupport.truncate(card.summary(), 80));
            item.put("authors", card.authors());
            item.put("dateLabel", card.dateLabel());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        // ⚠️ total 是【匹配到的总数】不是本页条数：模型要能回答"一共有多少个"，
        // 而不必把全部条目塞进上下文
        data.put("total", matched);
        data.put("items", items);
        // ⚠️ hint 是"引导链式调用"的低成本手段：在没有会话记忆的前提下，
        // 它比系统提示词更可靠（§4 P0-2 第 ④ 条）
        data.put("hint", "需要正文请调用 work_detail(slug)");
        return AiToolSupport.ok(mapper, data);
    }
}
