package com.unicorn.studio.AIWorker.tool.impl;

import com.unicorn.studio.AIWorker.tool.AiTool;
import com.unicorn.studio.AIWorker.tool.support.AiToolSupport;
import com.unicorn.studio.admin.service.LearningCategoryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习资料分类清单（工具层建议 §4 P0-6）。
 * <p>
 * <b>它解决的是"没有会话记忆时的意图澄清"问题</b>：用户问"有资料吗"，模型无法反问后再查
 * （§1.3 约束 1），所以要么把分类清单一次性给出、让用户下一轮明确，要么直接猜分类（易错）。
 */
@Component
public class LearningCategoriesTool implements AiTool {

    private final LearningCategoryService categoryService;

    public LearningCategoriesTool(LearningCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @Tool(name = "learning_categories", description = "列出学习资料的分类及每个分类下的资料数量。"
            + "当用户问「都有哪些方面的资料」「有前端相关的吗」但没有明确关键词时，"
            + "先调本工具给出分类清单，再由用户选择。")
    public Map<String, Object> learningCategories() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        long total = 0;
        //查询所有分类及资料数
        List<LearningCategoryService.CategoryItem> items = categoryService.listAll();
        if (items != null) {
            for (LearningCategoryService.CategoryItem item : items) {
                if (item == null || item.category() == null) {
                    continue;
                }
                // 获取分类名
                String name = item.category().getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                // 添加分类名
                c.put("name", name);
                // 添加资料数
                // ⚠️ count 来自 CategoryItem.resourceCount：Service 已经查好了，零新增查询
                //就是把显示在前台的数据直接带过来了
                c.put("count", item.resourceCount());
                categories.add(c);
                total += item.resourceCount();
            }
        }
        // 添加分类列表
        data.put("categories", categories);
        // 添加资料总数
        // ⚠️ total 是各分类资料数之和（不是分类个数）：模型要能答"一共有多少份资料"
        data.put("total", total);
        return AiToolSupport.ok(data);
    }
}
