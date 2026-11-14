package com.unicorn.studio.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.CapabilityMapper;
import com.unicorn.studio.vo.CapabilityVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class CapabilityService {

    private static final String PUBLISHED = "published";

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final JavaType LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, CapabilityVO.class);

    private final CapabilityMapper capabilityMapper;
    private final CacheHelper cache;

    public CapabilityService(CapabilityMapper capabilityMapper, CacheHelper cache) {
        this.capabilityMapper = capabilityMapper;
        this.cache = cache;
    }

    /** 我们培养的能力公开列表：仅已发布条目，按排序展示；Redis 缓存 30 min（B2）。 */
    public List<CapabilityVO> findPublished() {
        var snapshot = cache.snapshot(CacheKeys.HOME_CAPABILITIES);
        return snapshot.<List<CapabilityVO>>get(LIST_TYPE).orElseGet(() -> {
            List<CapabilityVO> list = capabilityMapper.findByStatusOrderBySortOrderAscIdAsc(PUBLISHED)
                    .stream()
                    .map(CapabilityVO::from)
                    .toList();
            snapshot.set(list, CACHE_TTL);
            return list;
        });
    }
}
