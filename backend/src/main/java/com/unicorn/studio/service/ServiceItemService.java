package com.unicorn.studio.service;

import com.unicorn.studio.model.ServiceItem;
import com.unicorn.studio.repository.ServiceItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServiceItemService {

    private static final String PUBLISHED = "published";

    private final ServiceItemRepository serviceItemRepository;

    public ServiceItemService(ServiceItemRepository serviceItemRepository) {
        this.serviceItemRepository = serviceItemRepository;
    }

    /** 公开列表：仅已发布条目，按排序展示。 */
    public List<ServiceItem> findPublished() {
        return serviceItemRepository.findByStatusOrderBySortOrderAscIdAsc(PUBLISHED);
    }
}
