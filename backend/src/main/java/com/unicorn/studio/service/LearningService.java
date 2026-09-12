package com.unicorn.studio.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.LearningResourceMapper;
import com.unicorn.studio.model.LearningResource;
import com.unicorn.studio.vo.LearningCardVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class LearningService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration NULL_TTL = Duration.ofSeconds(60);
    private static final JavaType LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, LearningCardVO.class);

    private final LearningResourceMapper repository;
    private final CacheHelper cache;

    public LearningService(LearningResourceMapper repository, CacheHelper cache) {
        this.repository = repository;
        this.cache = cache;
    }

    /** 公开列表：仅上架资料，按排序值升序；Redis 缓存 30 min（B5，返回不含 content 的卡片 VO）。 */
    public List<LearningCardVO> findAll() {
        return cache.<List<LearningCardVO>>get(CacheKeys.LEARNING_LIST, LIST_TYPE).orElseGet(() -> {
            List<LearningCardVO> list = repository.selectList(new LambdaQueryWrapper<LearningResource>()
                            .eq(LearningResource::getStatus, 1)
                            .orderByAsc(LearningResource::getSortOrder, LearningResource::getId))
                    .stream()
                    .map(LearningCardVO::from)
                    .toList();
            cache.set(CacheKeys.LEARNING_LIST, list, CACHE_TTL);
            return list;
        });
    }

    /** 公开详情：按 id 查询上架资料（含正文 content，不入缓存）；空值哨兵防穿透（§4.1 P1）。 */
    public Optional<LearningResource> findPublishedById(Long id) {
        if (cache.getString(CacheKeys.nullLearning(id)).isPresent()) {
            return Optional.empty();
        }
        Optional<LearningResource> resource = Optional.ofNullable(repository.selectOne(
                new LambdaQueryWrapper<LearningResource>()
                        .eq(LearningResource::getId, id)
                        .eq(LearningResource::getStatus, 1)));
        if (resource.isEmpty()) {
            cache.setString(CacheKeys.nullLearning(id), "__NULL__", NULL_TTL);
        }
        return resource;
    }
}
