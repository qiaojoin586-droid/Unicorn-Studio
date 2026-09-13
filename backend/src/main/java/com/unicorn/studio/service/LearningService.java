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

    /**
     * 公开列表：仅上架资料，按排序值升序；Redis 缓存 30 min（B5，返回不含 content 的卡片 VO）。
     * <p>
     * <b>查询必须做列投影（B-09）</b>：{@code learning_resources.content} 是 MEDIUMTEXT（上限 16MB），
     * 而 {@link LearningCardVO} 刻意不含正文 —— 此前 SQL 层照样把整列正文查了出来，
     * 再在 {@code LearningCardVO.from()} 里丢掉。{@code LearningCardVO} 的类注释专门解释了
     * 「列表接口避免过度返回，缓存也不放大字段」，但只做到 VO 这一层是无效的：
     * <b>字段是在 SQL 层被读出来的，VO 只是没有序列化它</b>，回源开销与缓存体积都没降下来。
     * <p>
     * 投影字段与 {@link LearningCardVO} 的记录分量一一对应，改 VO 时务必同步这里。
     */
    public List<LearningCardVO> findAll() {
        return cache.<List<LearningCardVO>>get(CacheKeys.LEARNING_LIST, LIST_TYPE).orElseGet(() -> {
            List<LearningCardVO> list = repository.selectList(new LambdaQueryWrapper<LearningResource>()
                            .select(LearningResource::getId, LearningResource::getTitle,
                                    LearningResource::getDescription, LearningResource::getUrl,
                                    LearningResource::getCategory, LearningResource::getTags,
                                    LearningResource::getCoverUrl, LearningResource::getAttachmentUrl,
                                    LearningResource::getSortOrder, LearningResource::getCreatedAt,
                                    LearningResource::getUpdatedAt)
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
