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

    /** 按分组读取（V12：专用页面配置，如 about 组）。 */
    default List<SiteSettings> findByGroupName(String groupName) {
        return selectList(new LambdaQueryWrapper<SiteSettings>()
                .eq(SiteSettings::getGroupName, groupName)
                .orderByAsc(SiteSettings::getSettingKey));
    }
}
