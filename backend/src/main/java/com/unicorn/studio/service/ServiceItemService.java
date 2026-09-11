package com.unicorn.studio.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.unicorn.studio.common.cache.CacheHelper;
import com.unicorn.studio.common.cache.CacheKeys;
import com.unicorn.studio.mapper.ServiceItemMapper;
import com.unicorn.studio.vo.ServiceItemVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class ServiceItemService {

    private static final String PUBLISHED = "published";

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final JavaType LIST_TYPE = TypeFactory.defaultInstance()
            .constructCollectionType(List.class, ServiceItemVO.class);

    private final ServiceItemMapper serviceItemMapper;
    private final CacheHelper cache;

    public ServiceItemService(ServiceItemMapper serviceItemMapper, CacheHelper cache) {
        this.serviceItemMapper = serviceItemMapper;
        this.cache = cache;
    }

    /** 主要招收方向公开列表：仅已发布条目，按排序展示；Redis 缓存 30 min（B1）。 */
    public List<ServiceItemVO> findPublished() {
        return cache.<List<ServiceItemVO>>get(CacheKeys.HOME_SERVICES, LIST_TYPE).orElseGet(() -> {
            List<ServiceItemVO> list = serviceItemMapper.findByStatusOrderBySortOrderAscIdAsc(PUBLISHED)
                    .stream()
                    .map(ServiceItemVO::from)
                    .toList();
            cache.set(CacheKeys.HOME_SERVICES, list, CACHE_TTL);
            return list;
        });
    }
}
