package com.unicorn.studio.service;

import com.unicorn.studio.mapper.CapabilityMapper;
import com.unicorn.studio.model.Capability;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CapabilityService {

    private static final String PUBLISHED = "published";

    private final CapabilityMapper capabilityMapper;

    public CapabilityService(CapabilityMapper capabilityMapper) {
        this.capabilityMapper = capabilityMapper;
    }

    /** 我们培养的能力公开列表：仅已发布条目，按排序展示。 */
    public List<Capability> findPublished() {
        return capabilityMapper.findByStatusOrderBySortOrderAscIdAsc(PUBLISHED);
    }
}
