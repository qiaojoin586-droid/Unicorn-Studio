package com.unicorn.studio.service;

import com.unicorn.studio.mapper.ServiceItemMapper;
import com.unicorn.studio.model.ServiceItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServiceItemService {

    private static final String PUBLISHED = "published";

    private final ServiceItemMapper serviceItemMapper;

    public ServiceItemService(ServiceItemMapper serviceItemMapper) {
        this.serviceItemMapper = serviceItemMapper;
    }

    /** 主要招收方向公开列表：仅已发布条目，按排序展示。 */
    public List<ServiceItem> findPublished() {
        return serviceItemMapper.findByStatusOrderBySortOrderAscIdAsc(PUBLISHED);
    }
}
