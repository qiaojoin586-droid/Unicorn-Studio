package com.unicorn.studio.AIWorker.tool.impl;

import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.service.NewsService;
import com.unicorn.studio.vo.NewsCardVO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已发布新闻列表（工具层建议 §4 P0-4）。
 * <p>
 * <b>为什么不和 work_list 合并成一个 content_list(type)</b>：模型对「作品」和「新闻」是两个不同的
 * 语义概念，分开的描述更容易被正确路由；合并后描述必然要写成"根据 type 参数…"，
 * 反而降低路由准确率。代价是工具数 +1，而 6~10 个工具的规模下完全可接受（§4 P0-4 第 ① 条）。
 */
@Component
public class NewsListTool implements AiTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;

    private final NewsService newsService;

    public NewsListTool(NewsService newsService) {
        this.newsService = newsService;
    }

    @Tool(name = "news_list", description = "列出工作室已发布的新闻/动态卡片（标题、slug、分类、摘要、发布时间）。"
            + "当用户问「最近有什么动态」「新闻」「活动」时调用。")
    public Map<String, Object> newsList(
            @ToolParam(description = "新闻分类，如「工作室动态」「学术活动」", required = false) String category,
            @ToolParam(description = "返回条数，默认 5，上限 10", required = false) Integer limit) {
        category = AiToolSupport.textArg(category);
        int maxItems = AiToolSupport.boundedLimit(limit, DEFAULT_LIMIT, MAX_LIMIT);

        // 获取新闻列表
        // ⚠️ 现在返回卡片 VO（同样是不让 MEDIUMTEXT 正文随列表整列进内存）
        List<NewsCardVO> source = newsService.findPublished(category);
        // 构建返回数据
        List<Map<String, Object>> items = new ArrayList<>();
        int matched = 0;
        for (NewsCardVO n : source) {
            matched++;
            // ⚠️ continue 不能 break：break 会让后面的不再计数，total 就变成"本页条数"了
            if (items.size() >= maxItems) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("slug", n.slug());
            item.put("title", n.title());
            item.put("category", n.category());
            item.put("summary", AiToolSupport.truncate(n.summary(), 80));
            // ⚠️ 转成 ISO 字符串而不是直接塞 LocalDateTime：直接序列化的话格式由全局配置决定
            // （可能是时间戳数组），模型读不懂
            item.put("publishedAt", n.publishedAt() == null ? null : n.publishedAt().toString());
            items.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", matched);
        data.put("items", items);
        return AiToolSupport.ok(data);
    }
}
