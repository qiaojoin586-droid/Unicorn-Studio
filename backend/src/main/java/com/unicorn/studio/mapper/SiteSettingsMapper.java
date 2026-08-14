package com.unicorn.studio.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unicorn.studio.model.SiteSettings;

import java.util.List;
import java.util.Optional;

public interface SiteSettingsMapper extends BaseMapper<SiteSettings> {

    default Optional<SiteSettings> findBySettingKey(String key) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<SiteSettings>().eq(SiteSettings::getSettingKey, key)));
    }

    default List<SiteSettings> findAllByOrderByGroupNameAscSettingKeyAsc() {
        return selectList(new LambdaQueryWrapper<SiteSettings>()
                .orderByAsc(SiteSettings::getGroupName, SiteSettings::getSettingKey));
    }
}
