package com.unicorn.studio.vo;

import com.unicorn.studio.model.ServiceItem;

/** 主要招收方向 VO：仅暴露展示所需字段，不含 status/排序/审计字段。 */
public record ServiceItemVO(
        Long id,
        String title,
        String description,
        String icon) {

    public static ServiceItemVO from(ServiceItem item) {
        return new ServiceItemVO(
                item.getId(),
                item.getTitle(),
                item.getDescription(),
                item.getIcon());
    }
}
