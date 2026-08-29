package com.unicorn.studio.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.unicorn.studio.common.BusinessException;
import com.unicorn.studio.mapper.LearningCategoryMapper;
import com.unicorn.studio.mapper.LearningResourceMapper;
import com.unicorn.studio.model.LearningCategory;
import com.unicorn.studio.model.LearningResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 学习资料分类管理：CRUD。
 * 资源表存分类名（字符串，向后兼容存量数据），故重命名时同步改写资源行；
 * 删除前检查引用，有资料引用时拒绝（409）。
 */
@Service
public class LearningCategoryService {

    private final LearningCategoryMapper categoryMapper;
    private final LearningResourceMapper resourceMapper;

    public LearningCategoryService(LearningCategoryMapper categoryMapper,
                                   LearningResourceMapper resourceMapper) {
        this.categoryMapper = categoryMapper;
        this.resourceMapper = resourceMapper;
    }

    /** 分类列表项：category + 引用该分类的资料数。 */
    public record CategoryItem(LearningCategory category, long resourceCount) {
    }

    public List<CategoryItem> listAll() {
        Map<String, Long> counts = resourceCounts();
        return categoryMapper.selectList(new LambdaQueryWrapper<LearningCategory>()
                        .orderByAsc(LearningCategory::getSortOrder, LearningCategory::getId))
                .stream()
                .map(c -> new CategoryItem(c, counts.getOrDefault(c.getName(), 0L)))
                .toList();
    }

    @Transactional
    public LearningCategory create(String name, String description, Integer sortOrder, Integer status) {
        String normalized = requireName(name);
        if (categoryMapper.findByName(normalized).isPresent()) {
            throw BusinessException.conflict("分类已存在");
        }
        LearningCategory category = LearningCategory.builder()
                .name(normalized)
                .description(description)
                .sortOrder(sortOrder == null ? 0 : sortOrder)
                .status(status == null ? 1 : status)
                .build();
        categoryMapper.insert(category);
        return category;
    }

    @Transactional
    public void update(Long id, String name, String description, Integer sortOrder, Integer status) {
        LearningCategory category = getOrThrow(id);
        if (name != null && !name.isBlank() && !name.trim().equals(category.getName())) {
            String newName = name.trim();
            if (categoryMapper.findByName(newName).isPresent()) {
                throw BusinessException.conflict("分类已存在");
            }
            // 资源表存分类名：重命名需同步改写引用行
            String oldName = category.getName();
            resourceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LearningResource>()
                    .eq(LearningResource::getCategory, oldName)
                    .set(LearningResource::getCategory, newName));
            category.setName(newName);
        }
        if (description != null) {
            category.setDescription(description);
        }
        if (sortOrder != null) {
            category.setSortOrder(sortOrder);
        }
        if (status != null) {
            category.setStatus(status);
        }
        categoryMapper.updateById(category);
    }

    @Transactional
    public void delete(Long id) {
        LearningCategory category = getOrThrow(id);
        long refs = resourceMapper.selectCount(new LambdaQueryWrapper<LearningResource>()
                .eq(LearningResource::getCategory, category.getName()));
        if (refs > 0) {
            throw BusinessException.conflict("仍有 " + refs + " 份资料引用该分类，无法删除");
        }
        categoryMapper.deleteById(id);
    }

    /** 每个分类名下的资料数（单次 group by 查询）。 */
    private Map<String, Long> resourceCounts() {
        Map<String, Long> counts = new HashMap<>();
        resourceMapper.selectMaps(new QueryWrapper<LearningResource>()
                        .select("category AS category", "COUNT(*) AS cnt")
                        .groupBy("category"))
                .forEach(row -> {
                    Object name = row.get("category");
                    Object cnt = row.get("cnt");
                    if (name != null && cnt instanceof Number n) {
                        counts.put(String.valueOf(name), n.longValue());
                    }
                });
        return counts;
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw BusinessException.badRequest("分类名称不能为空");
        }
        return name.trim();
    }

    private LearningCategory getOrThrow(Long id) {
        return Optional.ofNullable(categoryMapper.selectById(id))
                .orElseThrow(() -> BusinessException.notFound("分类不存在"));
    }
}
