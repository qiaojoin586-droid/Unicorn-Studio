package com.unicorn.studio.AIWorker.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unicorn.studio.model.SiteSettings;

import java.util.List;
import java.util.Optional;

/**
 * AI 配置 Mapper。
 * <p>
 * 与 {@code SiteSettingsMapper} 同表（{@code site_settings}），但按<b>分组职责</b>拆分：
 * AIWorker 模块只关心 {@code ai} 组，避免模块间互相引用到对方的专用 Mapper 而产生隐式耦合。
 * 实体直接复用 {@link SiteSettings}（KV 表结构统一），不重复定义 model。
 * <p>
 * 注意：这里<b>不</b>继承 {@code BaseMapper}，而是通过依赖 {@link com.unicorn.studio.mapper.SiteSettingsMapper}
 * 完成读写 —— 保证「同一张表只有一个 MyBatis-Plus Mapper 注册」，避免重复注册导致的解析冲突。
 */
public interface AiConfigMapper {

    /** 读取 ai 组全部配置（按 key 升序）。 */
    List<SiteSettings> findByGroupName(String groupName);

    /** 按 setting_key 读单条。 */
    Optional<SiteSettings> findBySettingKey(String key);

    /** 新增。 */
    int insert(SiteSettings setting);

    /** 按主键更新。 */
    int updateById(SiteSettings setting);
}
