package com.unicorn.studio.vo;

import com.unicorn.studio.model.Capability;

/** 我们培养的能力 VO：仅暴露展示所需字段，不含 status/排序/审计字段。 */
public record CapabilityVO(
        Long id,
        String title,
        String description,
        String icon) {

    public static CapabilityVO from(Capability item) {
        return new CapabilityVO(
                item.getId(),
                item.getTitle(),
                item.getDescription(),
                item.getIcon());
    }
}
